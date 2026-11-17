"""Boolean queries over a render's raw rasters.

An expression is parsed with `ast.parse(mode="eval")` and walked by `Query`, which accepts only
the node types listed in `Query.evaluate` and evaluates them over numpy arrays itself. Nothing is
passed to `eval` or `compile`, names resolve only to rasters, and the only call is `abs(...)`.

Names: every raster in raw/ by its file stem (surface, water, habitat, landform, ...), except the
per-stage layers, which are `stage.<name>.delta` and `stage.<name>.reason`; the flag bits
(marine, supplied, deepOcean, wet); derived `slope` (blocks of rise per block) and the column
coordinates `x` and `z`. Categorical rasters compare with their names as strings, for example
`landform == "MOUNTAIN"` or `stage.cut.reason in ("ACCEPTED", "NO_SITE")`.
"""

import ast
import csv
import hashlib
import json
from collections.abc import Callable
from pathlib import Path

import numpy as np

from atlas.batch import CODED_LAYERS, DTYPES
from atlas.paint import Atlas, hillshade, save

STAGE_PARTS = {"delta": ("delta_{}.f32", "<f4"), "reason": ("reason_{}.u8", np.uint8)}
PER_STAGE = ("delta_", "family_", "reason_", "fp_delta_", "fp_family_")
COMPARE: dict[type, Callable] = {
    ast.Eq: np.equal,
    ast.NotEq: np.not_equal,
    ast.Lt: np.less,
    ast.LtE: np.less_equal,
    ast.Gt: np.greater,
    ast.GtE: np.greater_equal,
}
ARITHMETIC: dict[type, Callable] = {
    ast.Add: np.add,
    ast.Sub: np.subtract,
    ast.Mult: np.multiply,
    ast.Div: np.true_divide,
    ast.Mod: np.mod,
}


class QueryError(ValueError):
    pass


class Value:
    """An evaluated operand: an array (or number) and, for categorical rasters, its names."""

    def __init__(self, array, names: list[str] | None = None):
        self.array, self.names = array, names


class Query:
    def __init__(self, atlas: Atlas):
        self.atlas = atlas
        self.notes: list[str] = []
        self.layers = {}
        for path in sorted(atlas.raw.iterdir()):
            dtype = DTYPES.get(path.suffix)
            if dtype is not None and not path.name.startswith(PER_STAGE):
                self.layers[path.stem] = (path.name, dtype)

    def names(self) -> list[str]:
        derived = [*self.atlas.meta.get("flags", {}), "slope", "x", "z"]
        stages = [f"stage.{s}.{part}" for s in self.atlas.stages for part in STAGE_PARTS]
        return sorted(self.layers) + derived + stages

    def run(self, expression: str):
        """Boolean mask of the columns where the expression holds."""
        try:
            tree = ast.parse(expression, mode="eval")
            result = self.evaluate(tree.body).array
        except SyntaxError as error:
            raise QueryError(f"not an expression: {error.msg}") from None
        except RecursionError, MemoryError:
            raise QueryError("expression too deeply nested") from None
        if not isinstance(result, np.ndarray) or result.dtype != bool:
            raise QueryError("the expression must be a comparison or a boolean combination")
        return result

    def evaluate(self, node: ast.AST) -> Value:
        """The whitelist: every accepted node type has a branch; anything else is refused."""
        if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            combine = np.logical_and if isinstance(node.op, ast.And) else np.logical_or
            values = [self.boolean(v) for v in node.values]
            result = values[0]
            for v in values[1:]:
                result = combine(result, v)
            return Value(result)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            return Value(np.logical_not(self.boolean(node.operand)))
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
            operand = self.number(node.operand)
            return Value(-operand if isinstance(node.op, ast.USub) else operand)
        if isinstance(node, ast.BinOp) and type(node.op) in ARITHMETIC:
            left, right = self.number(node.left), self.number(node.right)
            with np.errstate(divide="ignore", invalid="ignore"):
                return Value(ARITHMETIC[type(node.op)](left, right))
        if isinstance(node, ast.Compare):
            return Value(self.compare(node))
        if (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "abs"
            and len(node.args) == 1
            and not node.keywords
        ):
            return Value(np.abs(self.number(node.args[0])))
        if isinstance(node, ast.Name):
            return self.name(node.id)
        if isinstance(node, ast.Attribute):
            return self.stage(node)
        if isinstance(node, ast.Constant) and type(node.value) in (int, float, str):
            return Value(node.value)
        raise QueryError(f"not allowed in a query: {ast.unparse(node)!r}")

    def boolean(self, node: ast.AST):
        value = self.evaluate(node).array
        if not isinstance(value, np.ndarray) or value.dtype != bool:
            raise QueryError(f"{ast.unparse(node)!r} is not a condition")
        return value

    def number(self, node: ast.AST):
        value = self.evaluate(node)
        if value.names is not None or isinstance(value.array, str):
            raise QueryError(f"{ast.unparse(node)!r} is categorical; compare it with a name")
        if isinstance(value.array, np.ndarray) and value.array.dtype == bool:
            raise QueryError(f"{ast.unparse(node)!r} is a condition, not a number")
        return value.array

    def compare(self, node: ast.Compare):
        result = None
        left = node.left
        for op, right in zip(node.ops, node.comparators, strict=True):
            if isinstance(op, (ast.In, ast.NotIn)):
                part = self.membership(left, right)
                part = np.logical_not(part) if isinstance(op, ast.NotIn) else part
            elif type(op) in COMPARE:
                a, b = self.evaluate(left), self.evaluate(right)
                if isinstance(a.array, str) or isinstance(b.array, str):
                    if type(op) not in (ast.Eq, ast.NotEq):
                        raise QueryError("names compare only with == and !=")
                    a, b = (a, b) if isinstance(b.array, str) else (b, a)
                    b = Value(self.code(a, b.array))
                elif a.names is not None or b.names is not None:
                    raise QueryError("a categorical raster compares only with a name")
                part = COMPARE[type(op)](a.array, b.array)
            else:
                raise QueryError(f"comparison not allowed: {type(op).__name__}")
            result = part if result is None else np.logical_and(result, part)
            left = right
        return result

    def membership(self, left: ast.AST, right: ast.AST):
        if not isinstance(right, (ast.Tuple, ast.List)) or not all(
            isinstance(e, ast.Constant) and type(e.value) in (int, float, str) for e in right.elts
        ):
            raise QueryError("`in` needs a literal tuple or list of names or numbers")
        items = [e.value for e in right.elts if isinstance(e, ast.Constant)]
        value = self.evaluate(left)
        if not isinstance(value.array, np.ndarray) or value.array.dtype == bool:
            raise QueryError("`in` needs a raster on its left")
        named = [isinstance(item, str) for item in items]
        if value.names is not None and not all(named):
            raise QueryError("a categorical raster compares only with names")
        if value.names is None and any(named):
            raise QueryError("names compare only with a categorical raster")
        codes = [self.code(value, item) if isinstance(item, str) else item for item in items]
        return np.isin(value.array, codes)

    def code(self, value: Value, label: str) -> int:
        if value.names is None:
            raise QueryError(f"{label!r} compared with a raster that has no names")
        if label in value.names:
            return value.names.index(label)
        # Lists hold what this window recorded, so a name missing here may be valid elsewhere.
        self.notes.append(f"{label!r} does not occur in this render; it matches nothing")
        return -1

    def name(self, identifier: str) -> Value:
        meta = self.atlas.meta
        if identifier in self.layers:
            file, dtype = self.layers[identifier]
            array = self.atlas.layer(file, dtype)
            names = meta.get(CODED_LAYERS[file]) if file in CODED_LAYERS else None
            return Value(array.astype(np.int64) if names is not None else array, names)
        if identifier in meta.get("flags", {}):
            return Value((self.atlas.layer("flags.u8", np.uint8) & meta["flags"][identifier]) != 0)
        if identifier == "slope":
            surface = self.atlas.layer("surface.i16", "<i2").astype(np.float32)
            gz, gx = np.gradient(surface, self.atlas.step)
            return Value(np.hypot(gx, gz))
        if identifier in ("x", "z"):
            rows, cols = np.indices(self.atlas.shape)
            index = cols if identifier == "x" else rows
            return Value(meta["minX" if identifier == "x" else "minZ"] + index * self.atlas.step)
        raise QueryError(f"unknown name {identifier!r}; known: {', '.join(self.names())}")

    def stage(self, node: ast.Attribute) -> Value:
        inner = node.value
        if not (
            isinstance(inner, ast.Attribute)
            and isinstance(inner.value, ast.Name)
            and inner.value.id == "stage"
            and node.attr in STAGE_PARTS
        ):
            raise QueryError(f"not allowed in a query: {ast.unparse(node)!r}")
        stage = inner.attr
        if stage not in self.atlas.stages:
            raise QueryError(f"no stage {stage!r}; stages: {', '.join(self.atlas.stages)}")
        pattern, dtype = STAGE_PARTS[node.attr]
        array = self.atlas.layer(pattern.format(stage), dtype)
        if node.attr == "reason":
            return Value(array.astype(np.int64), self.atlas.stage_meta(stage)["reasons"])
        return Value(array)


def run_query(render: Path, expression: str, csv_path: Path | None, out: Path | None) -> dict:
    """Evaluate one expression over a render; writes query.png, query.json and optionally CSV."""
    atlas = Atlas(render / "raw")
    q = Query(atlas)
    mask = q.run(expression)
    key = hashlib.sha256(expression.encode()).hexdigest()[:10]
    out = out or render / "query" / key
    out.mkdir(parents=True, exist_ok=True)
    surface = atlas.layer("surface.i16", "<i2").astype(np.float32)
    shade = hillshade(surface, atlas.step)[..., None]
    water = atlas.layer("water.i16", "<i2").astype(np.float32) > surface
    base = np.where(water[..., None], np.array([50, 80, 120]), 70 + 120 * shade)
    image = np.where(mask[..., None], np.array([255, 60, 200]) * (0.6 + 0.5 * shade), base)
    save(image, out / "query.png")
    count = int(mask.sum())
    result = {
        "render": str(render),
        "expression": expression,
        "columns": count,
        "total": int(mask.size),
        "sharePct": round(100.0 * count / mask.size, 3),
        "digest": atlas.meta.get("digest"),
        "notes": q.notes,
    }
    (out / "query.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    if csv_path is not None:
        rows, cols = np.nonzero(mask)
        with csv_path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(["x", "z"])
            step = atlas.step
            for r, c in zip(rows.tolist(), cols.tolist(), strict=True):
                writer.writerow([atlas.meta["minX"] + c * step, atlas.meta["minZ"] + r * step])
    return result | {"out": str(out)}


def run(args) -> None:
    """The `query` subcommand."""
    try:
        result = run_query(args.render, args.where, args.csv, args.out)
    except QueryError as error:
        raise SystemExit(f"query: {error}") from None
    for note in result["notes"]:
        print(f"note: {note}")
    print(
        f"{result['columns']} of {result['total']} columns match ({result['sharePct']}%)"
        f" -> {Path(result['out']) / 'query.png'}"
    )
