import os
import numpy as np
from PIL import Image
from scipy import ndimage

SRC = 'logo_source.jpg'
BASE = '/data/workspace/BenStore/app/src/main/res'

im = Image.open(SRC).convert('RGB')
a0 = np.asarray(im).astype(np.float32)
H, W, _ = a0.shape

# ---------- 1. 抠出机器人（背景是横向彩虹渐变，按列建模后取差异） ----------
top = a0[0:12, :, :].mean(axis=0)                 # W,3 渐变基准
bg = np.repeat(top[None, :, :], H, axis=0)
dist = np.sqrt(((a0 - bg) ** 2).sum(axis=2))

m = dist > 55
m = ndimage.binary_closing(m, np.ones((7, 7)))
m = ndimage.binary_closing(m, np.ones((25, 3)))   # 垂直方向接上腿
lab, n = ndimage.label(m)
sizes = ndimage.sum(m, lab, range(1, n + 1))
keep = [i + 1 for i in range(n) if sizes[i] > 5000]
sel = np.isin(lab, keep)
sel = ndimage.binary_fill_holes(sel)
sel = ndimage.binary_closing(sel, np.ones((5, 5)))
sel = ndimage.binary_erosion(sel, np.ones((3, 3)))  # 去掉背景光晕
sel = ndimage.binary_fill_holes(sel)

ys, xs = np.where(sel)
y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1

alpha_f = np.clip(ndimage.gaussian_filter(sel.astype(np.float32), sigma=1.6), 0, 1)
robot = np.zeros((y1 - y0, x1 - x0, 4), np.float32)
robot[..., :3] = a0[y0:y1, x0:x1]
robot[..., 3] = alpha_f[y0:y1, x0:x1] * 255
ROBOT_RATIO = robot.shape[0] / robot.shape[1]     # 高/宽


# ---------- 2. 彩虹渐变背景 ----------
def rainbow(width, height):
    xs_src = np.linspace(0, W - 1, W)
    xt = np.linspace(0, W - 1, width)
    out = np.zeros((height, width, 3), np.float32)
    for c in range(3):
        out[..., c] = np.interp(xt, xs_src, top[:, c])
    return out


def _resize_robot(tw, th):
    body = np.zeros((th, tw, 4), np.float32)
    for c in range(4):
        ch = Image.fromarray(robot[..., c].astype(np.uint8), 'L').resize((tw, th), Image.LANCZOS)
        body[..., c] = np.asarray(ch).astype(np.float32)
    return body


def _place(size, arr, tw, th):
    ox, oy = (size - tw) // 2, (size - th) // 2
    return ox, oy


def _stroke(a_b, size, stroke_ratio):
    """沿机器人轮廓向外扩一圈白色描边；不填内部空隙，保留双腿形状"""
    r = max(1.0, size * stroke_ratio)
    dil = ndimage.binary_dilation(a_b > 0.35, structure=np.ones((3, 3)),
                                  iterations=int(max(1, round(r))))
    return np.clip(ndimage.gaussian_filter(dil.astype(np.float32), sigma=max(0.5, r * 0.4)), 0, 1)


BODY = 72 / 108.0          # 机器人在 108dp 画布上的高度占比（贴合官方 72dp 可视区）
STROKE = 0.014


def compose(size):
    canvas = rainbow(size, size)
    th = int(round(size * BODY))
    tw = max(1, int(round(th / ROBOT_RATIO)))
    body = _resize_robot(tw, th)
    a_b = (body[..., 3:4] / 255.0)
    sa = _stroke(a_b[..., 0], size, STROKE)[..., None]
    ox, oy = _place(size, None, tw, th)
    sub = canvas[oy:oy + th, ox:ox + tw]
    out = sub * (1 - sa) + np.array([255, 255, 255], np.float32) * sa
    out = out * (1 - a_b) + body[..., :3] * a_b
    arr = canvas.copy()
    arr[oy:oy + th, ox:ox + tw] = out
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), 'RGB')


def foreground(size):
    th = int(round(size * BODY))
    tw = max(1, int(round(th / ROBOT_RATIO)))
    body = _resize_robot(tw, th)
    a_b = body[..., 3] / 255.0
    sa = _stroke(a_b, size, STROKE)
    out_a = np.clip(sa + a_b * (1 - sa), 0, 1)
    out_rgb = (np.array([255, 255, 255], np.float32) * (sa * (1 - a_b))[..., None]
               + body[..., :3] * a_b[..., None])
    full = np.zeros((size, size, 4), np.float32)
    ox, oy = _place(size, out_a, tw, th)
    full[oy:oy + th, ox:ox + tw, :3] = out_rgb
    full[oy:oy + th, ox:ox + tw, 3] = out_a * 255
    return Image.fromarray(np.clip(full, 0, 255).astype(np.uint8), 'RGBA')


os.makedirs(BASE + '/drawable-nodpi', exist_ok=True)
os.makedirs(BASE + '/mipmap-anydpi-v26', exist_ok=True)
os.makedirs('/data/workspace/BenStore/art', exist_ok=True)

foreground(432).save(BASE + '/drawable-nodpi/ic_launcher_foreground.png')
Image.fromarray(rainbow(432, 432).astype(np.uint8), 'RGB').save(
    BASE + '/drawable-nodpi/ic_launcher_background.png')

for folder, size in [('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
                     ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)]:
    d = f'{BASE}/{folder}'
    os.makedirs(d, exist_ok=True)
    compose(size).save(d + '/ic_launcher.png')
    print('legacy', folder, size)

compose(512).save('/data/workspace/BenStore/art/ic_launcher_playstore.png')
im.save('/data/workspace/BenStore/art/logo_original.jpg', quality=92)
print('robot bbox ratio %.2f' % ROBOT_RATIO)
print('done')
