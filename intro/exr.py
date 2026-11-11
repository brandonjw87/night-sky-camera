"""Reading and writing EXR files with the OpenEXR module (float32 arrays, channel layers by name)."""
import numpy as np
import OpenEXR


def write_rgb(path, img):
    img = np.ascontiguousarray(img.astype(np.float32))
    header = {"compression": OpenEXR.ZIP_COMPRESSION, "type": OpenEXR.scanlineimage}
    OpenEXR.File(header, {"RGB": img}).write(path)


def read_layers(path):
    """All channels of a (multilayer) EXR as {name: float32 array}; RGB(A) groups come as (h, w, 3 or 4)."""
    out = {}
    with OpenEXR.File(path) as f:
        for part in f.parts:
            for name, ch in part.channels.items():
                out[name] = np.asarray(ch.pixels, dtype=np.float32)
    return out
