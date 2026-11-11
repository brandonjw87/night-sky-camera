"""The intro's 3D scene, rendered with Blender's Cycles: the name as bevelled metal letters floating over dark
ground, lit by lights that are each rendered to their own pass (so the flash and the lit look are timed afterwards).

    python3 scene.py --frames 0 131 --out DIR [--scale 100] [--samples 96]

Each frame is a multilayer EXR: Combined (with alpha: the sky is put in behind afterwards) and one pass per light.
"""
import argparse
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import intro_common as C  # noqa: E402
import lettering  # noqa: E402

GROUPS = ['env', 'rim', 'flash', 'key', 'sweep', 'made']


def principled(name, colour, metallic, rough, coat=0.0, coat_rough=0.05, emission=None, strength=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (*colour, 1.0)
    b.inputs['Metallic'].default_value = metallic
    b.inputs['Roughness'].default_value = rough
    if coat > 0:
        b.inputs['Coat Weight'].default_value = coat
        b.inputs['Coat Roughness'].default_value = coat_rough
    if emission is not None:
        b.inputs['Emission Color'].default_value = (*emission, 1.0)
        b.inputs['Emission Strength'].default_value = strength
    return m


def face_material(boxes):
    """The letters' fronts: satin metal brushed sideways, each line a little darker towards its foot."""
    m = principled('face', (0.95, 0.925, 0.88), 1.0, 0.60, coat=0.6, coat_rough=0.07)
    nt = m.node_tree
    b = nt.nodes['Principled BSDF']
    tc = nt.nodes.new('ShaderNodeTexCoord')
    sep = nt.nodes.new('ShaderNodeSeparateXYZ')
    nt.links.new(tc.outputs['Object'], sep.inputs[0])

    def ramp(lo, hi):
        n = nt.nodes.new('ShaderNodeMapRange')
        n.inputs['From Min'].default_value = lo
        n.inputs['From Max'].default_value = hi
        n.inputs['To Min'].default_value = 0.0
        n.inputs['To Max'].default_value = 1.0
        n.clamp = True
        nt.links.new(sep.outputs[1], n.inputs['Value'])
        return n
    l1, l2 = boxes['line1'], boxes['line2']
    g1 = ramp(l1[1], l1[3])
    g2 = ramp(l2[1], l2[3])
    upper = nt.nodes.new('ShaderNodeMath')
    upper.operation = 'GREATER_THAN'
    upper.inputs[1].default_value = 0.5 * (l2[3] + l1[1])
    nt.links.new(sep.outputs[1], upper.inputs[0])
    pick = nt.nodes.new('ShaderNodeMix')
    pick.data_type = 'FLOAT'
    nt.links.new(upper.outputs[0], pick.inputs[0])
    nt.links.new(g2.outputs['Result'], pick.inputs[2])
    nt.links.new(g1.outputs['Result'], pick.inputs[3])
    mp = nt.nodes.new('ShaderNodeMapping')
    mp.inputs['Scale'].default_value = (0.5, 46.0, 1.0)
    noise = nt.nodes.new('ShaderNodeTexNoise')
    noise.inputs['Scale'].default_value = 1.0
    noise.inputs['Detail'].default_value = 4.0
    nt.links.new(tc.outputs['Object'], mp.inputs['Vector'])
    nt.links.new(mp.outputs['Vector'], noise.inputs['Vector'])
    # value = (0.74 + 0.26 * gradient) * (0.95 + 0.10 * brush)
    v1 = nt.nodes.new('ShaderNodeMath'); v1.operation = 'MULTIPLY_ADD'
    v1.inputs[1].default_value = 0.26; v1.inputs[2].default_value = 0.74
    nt.links.new(pick.outputs[0], v1.inputs[0])
    v2 = nt.nodes.new('ShaderNodeMath'); v2.operation = 'MULTIPLY_ADD'
    v2.inputs[1].default_value = 0.10; v2.inputs[2].default_value = 0.95
    nt.links.new(noise.outputs['Fac'], v2.inputs[0])
    v = nt.nodes.new('ShaderNodeMath'); v.operation = 'MULTIPLY'
    nt.links.new(v1.outputs[0], v.inputs[0])
    nt.links.new(v2.outputs[0], v.inputs[1])
    col = nt.nodes.new('ShaderNodeVectorMath'); col.operation = 'SCALE'
    col.inputs[0].default_value = (0.95, 0.925, 0.88)
    nt.links.new(v.outputs[0], col.inputs['Scale'])
    nt.links.new(col.outputs[0], b.inputs['Base Color'])
    rough = nt.nodes.new('ShaderNodeMath'); rough.operation = 'MULTIPLY_ADD'
    rough.inputs[1].default_value = 0.16; rough.inputs[2].default_value = 0.50
    nt.links.new(noise.outputs['Fac'], rough.inputs[0])
    nt.links.new(rough.outputs[0], b.inputs['Roughness'])
    return m


def emitter(name, colour, strength, gradient=None):
    """An emission material; gradient = (axis index, low, high factor) fades it along the object's own axis."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new('ShaderNodeOutputMaterial')
    em = nt.nodes.new('ShaderNodeEmission')
    em.inputs['Color'].default_value = (*colour, 1.0)
    em.inputs['Strength'].default_value = strength
    if gradient is not None:
        axis, lo, hi = gradient
        tc = nt.nodes.new('ShaderNodeTexCoord')
        sep = nt.nodes.new('ShaderNodeSeparateXYZ')
        nt.links.new(tc.outputs['Generated'], sep.inputs[0])
        ramp = nt.nodes.new('ShaderNodeMapRange')
        ramp.inputs['From Min'].default_value = 0.0
        ramp.inputs['From Max'].default_value = 1.0
        ramp.inputs['To Min'].default_value = lo * strength
        ramp.inputs['To Max'].default_value = hi * strength
        nt.links.new(sep.outputs[axis], ramp.inputs['Value'])
        nt.links.new(ramp.outputs['Result'], em.inputs['Strength'])
    nt.links.new(em.outputs[0], out.inputs['Surface'])
    try:
        m.cycles.emission_sampling = 'FRONT'
    except Exception as e:
        print('emission sampling:', e)
    return m


def curve_object(name, contours, extrude, bevel, bevel_res):
    cu = bpy.data.curves.new(name, 'CURVE')
    cu.dimensions = '2D'
    cu.fill_mode = 'BOTH'
    cu.resolution_u = 20
    cu.extrude = extrude
    cu.bevel_depth = bevel
    cu.bevel_resolution = bevel_res
    cu.offset = -bevel
    for con in contours:
        sp = cu.splines.new('BEZIER')
        n = len(con)
        sp.bezier_points.add(n - 1)
        for i, seg in enumerate(con):
            p0, c1, c2, p1 = seg
            prev = con[i - 1]
            bp = sp.bezier_points[i]
            bp.co = (p0[0], p0[1], 0.0)
            # outgoing handle from this segment, incoming from the one before
            if c1 is None:
                hr = (p0[0] + (p1[0] - p0[0]) / 3.0, p0[1] + (p1[1] - p0[1]) / 3.0)
            else:
                hr = c1
            if prev[2] is None:
                q0 = prev[0]
                hl = (p0[0] + (q0[0] - p0[0]) / 3.0, p0[1] + (q0[1] - p0[1]) / 3.0)
            else:
                hl = prev[2]
            bp.handle_left_type = 'FREE'
            bp.handle_right_type = 'FREE'
            bp.handle_right = (hr[0], hr[1], 0.0)
            bp.handle_left = (hl[0], hl[1], 0.0)
        sp.use_cyclic_u = True
    ob = bpy.data.objects.new(name, cu)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def to_mesh(ob, mats, bevel_split=True):
    """Bake a curve object to a mesh with materials by which way each face looks: front, bevel, side."""
    deps = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(deps))
    name = ob.name
    bpy.data.objects.remove(ob)
    mo = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(mo)
    for m in mats:
        me.materials.append(m)
    n = len(me.polygons)
    normals = np.empty(n * 3, np.float32)
    me.polygons.foreach_get('normal', normals)
    nz = normals.reshape(n, 3)[:, 2]
    idx = np.zeros(n, np.int32)
    if bevel_split and len(mats) >= 3:
        idx[(nz < 0.985) & (nz > 0.12)] = 1
        idx[nz <= 0.12] = 2
    me.polygons.foreach_set('material_index', idx)
    try:
        me.shade_smooth()
        me.set_sharp_from_angle(angle=math.radians(28.0))
    except Exception as e:  # older API names
        print('smooth shading:', e)
    me.update()
    return mo


def card(name, centre, x_axis, y_axis, size, colour, radiance, group, receivers, target):
    """A rectangular lamp the camera can't see: it lights only the lettering, and only the lettering shades it
    (the ground doesn't get in its way). radiance is what a mirror facing it would show."""
    la = bpy.data.lights.new(name, 'AREA')
    la.shape = 'RECTANGLE'
    la.size = size[0]
    la.size_y = size[1]
    la.color = colour
    # Cycles: an area lamp of P watts over area A shows radiance P / (4 A) in its own colour
    la.energy = radiance * 4.0 * size[0] * size[1]
    ob = bpy.data.objects.new(name, la)
    bpy.context.scene.collection.objects.link(ob)
    x = Vector(x_axis).normalized()
    y = Vector(y_axis).normalized()
    z = x.cross(y)
    if z.dot(Vector(target) - Vector(centre)) > 0:   # a lamp shines along its own -Z
        x = -x
        z = x.cross(y)
    m = Matrix((x, y, z)).transposed().to_4x4()
    m.translation = Vector(centre)
    ob.matrix_world = m
    ob.visible_camera = False
    ob.lightgroup = group
    ob.light_linking.receiver_collection = receivers
    ob.light_linking.blocker_collection = receivers
    return ob


def terrain():
    """Dark ground: a pebbly shore under the camera, a still lake beyond it, low hills on the far side."""
    import sky
    nr, na = 360, 900
    r = 0.5 * (9000.0 / 0.5) ** (np.arange(nr) / (nr - 1.0))
    a = np.arange(na) * (2.0 * math.pi / na)
    R, A = np.meshgrid(r, a, indexing='ij')
    n1 = sky.fbm((nr, na), 2.6, 31)
    n2 = sky.fbm((nr, na), 2.2, 32, lo=0.01)
    skyline = ndimage_smooth(sky.fbm((1, na), 2.4, 34)[0], 2.0)
    shore = ndimage_smooth(sky.fbm((1, na), 2.6, 35)[0], 6.0)

    def ramp(x, a0, a1):
        t = np.clip((x - a0) / (a1 - a0), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    r_shore = 13.5 + 2.2 * shore[None, :]
    land = 0.26 - 0.95 * ramp(R, r_shore - 3.5, r_shore + 7.0) + 0.035 * n2 * (1.0 - ramp(R, 20.0, 60.0))
    hills = ramp(R, 1500.0, 3800.0) * (62.0 + 46.0 * skyline[None, :]) * (0.55 + 0.45 * (1.0 - np.abs(np.tanh(n1)))) \
        * (1.0 - 0.5 * ramp(R, 5200.0, 9000.0))
    far_shore = 1.2 * ramp(R, 1150.0, 1500.0)
    Z = land + hills + far_shore
    cx, cy = 0.0, -17.5
    X = cx + R * np.sin(A)
    Y = cy + R * np.cos(A)
    verts = np.stack([X, Y, Z], axis=-1).reshape(-1, 3)
    verts = np.concatenate([verts, np.array([[cx, cy, 0.26]])])
    faces = []
    for i in range(nr - 1):
        base, nxt = i * na, (i + 1) * na
        j = np.arange(na)
        j2 = (j + 1) % na
        faces.append(np.stack([base + j, base + j2, nxt + j2, nxt + j], axis=1))
    faces = np.concatenate(faces)
    centre = len(verts) - 1
    fan = [(centre, (j + 1) % na, j) for j in range(na)]
    me = bpy.data.meshes.new('ground')
    me.from_pydata(verts.tolist(), [], faces.tolist() + fan)
    me.shade_smooth()
    m = bpy.data.materials.new('ground')
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (0.062, 0.055, 0.050, 1.0)
    b.inputs['Roughness'].default_value = 0.70
    tc = nt.nodes.new('ShaderNodeTexCoord')
    noise = nt.nodes.new('ShaderNodeTexNoise')
    noise.inputs['Scale'].default_value = 7.0
    noise.inputs['Detail'].default_value = 12.0
    noise.inputs['Roughness'].default_value = 0.66
    vor = nt.nodes.new('ShaderNodeTexVoronoi')
    vor.inputs['Scale'].default_value = 22.0
    mix = nt.nodes.new('ShaderNodeMath')
    mix.operation = 'ADD'
    scale = nt.nodes.new('ShaderNodeMath')
    scale.operation = 'MULTIPLY'
    scale.inputs[1].default_value = -0.6
    bump = nt.nodes.new('ShaderNodeBump')
    bump.inputs['Strength'].default_value = 0.9
    bump.inputs['Distance'].default_value = 0.025
    nt.links.new(tc.outputs['Object'], noise.inputs['Vector'])
    nt.links.new(tc.outputs['Object'], vor.inputs['Vector'])
    nt.links.new(vor.outputs['Distance'], scale.inputs[0])
    nt.links.new(noise.outputs['Fac'], mix.inputs[0])
    nt.links.new(scale.outputs[0], mix.inputs[1])
    nt.links.new(mix.outputs[0], bump.inputs['Height'])
    nt.links.new(bump.outputs['Normal'], b.inputs['Normal'])
    me.materials.append(m)
    ob = bpy.data.objects.new('ground', me)
    bpy.context.scene.collection.objects.link(ob)

    # the lake: a mirror with slow ripples that smear what it reflects
    wm = bpy.data.meshes.new('water')
    s = 12000.0
    wm.from_pydata([(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)], [], [(0, 1, 2, 3)])
    wmat = bpy.data.materials.new('water')
    wmat.use_nodes = True
    nt = wmat.node_tree
    b = nt.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (0.0, 0.0, 0.0, 1.0)
    b.inputs['Roughness'].default_value = 0.035
    b.inputs['IOR'].default_value = 1.333
    tc = nt.nodes.new('ShaderNodeTexCoord')
    mp = nt.nodes.new('ShaderNodeMapping')
    mp.inputs['Scale'].default_value = (0.35, 1.6, 1.0)
    wn = nt.nodes.new('ShaderNodeTexNoise')
    wn.inputs['Scale'].default_value = 1.0
    wn.inputs['Detail'].default_value = 3.0
    wb = nt.nodes.new('ShaderNodeBump')
    wb.inputs['Strength'].default_value = 0.22
    wb.inputs['Distance'].default_value = 0.02
    nt.links.new(tc.outputs['Object'], mp.inputs['Vector'])
    nt.links.new(mp.outputs['Vector'], wn.inputs['Vector'])
    nt.links.new(wn.outputs['Fac'], wb.inputs['Height'])
    nt.links.new(wb.outputs['Normal'], b.inputs['Normal'])
    wm.materials.append(wmat)
    wo = bpy.data.objects.new('water', wm)
    bpy.context.scene.collection.objects.link(wo)

    return ob


def ndimage_smooth(x, sigma):
    from scipy import ndimage
    return ndimage.gaussian_filter1d(x, sigma, mode='wrap')


def build(args):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = 'CYCLES'
    sc.cycles.device = 'CPU'
    sc.cycles.samples = args.samples
    sc.cycles.use_adaptive_sampling = True
    sc.cycles.adaptive_threshold = 0.012
    sc.cycles.adaptive_min_samples = 24
    sc.cycles.use_denoising = False
    sc.cycles.max_bounces = 5
    sc.cycles.glossy_bounces = 4
    sc.cycles.diffuse_bounces = 1
    sc.cycles.transmission_bounces = 0
    sc.cycles.volume_bounces = 0
    sc.cycles.caustics_reflective = False
    sc.cycles.caustics_refractive = False
    sc.cycles.sample_clamp_indirect = 6.0
    sc.cycles.pixel_filter_type = 'BLACKMAN_HARRIS'
    sc.cycles.filter_width = 1.5
    sc.render.film_transparent = True
    sc.render.resolution_x = C.W
    sc.render.resolution_y = C.H
    sc.render.resolution_percentage = args.scale
    sc.render.threads_mode = 'FIXED'
    sc.render.threads = args.threads
    sc.render.fps = C.FPS
    sc.frame_start = 0
    sc.frame_end = C.NF - 1
    sc.render.use_persistent_data = True
    sc.render.use_motion_blur = True
    sc.render.motion_blur_shutter = 0.5
    try:
        sc.render.motion_blur_position = 'CENTER'
    except Exception as e:
        print('motion blur position:', e)
    sc.view_settings.view_transform = 'Standard'
    try:
        sc.render.image_settings.media_type = 'MULTI_LAYER_IMAGE'
    except Exception as e:
        print('media_type:', e)
    sc.render.image_settings.file_format = 'OPEN_EXR_MULTILAYER'
    sc.render.image_settings.color_depth = '16'
    sc.render.image_settings.exr_codec = 'ZIP'

    vl = sc.view_layers[0]
    for g in GROUPS:
        vl.lightgroups.add(name=g)

    # the world: the sky's soft glow, for reflections and the faint light on the ground
    world = bpy.data.worlds.new('sky')
    sc.world = world
    world.use_nodes = True
    nt = world.node_tree
    bg = nt.nodes['Background']
    tex = nt.nodes.new('ShaderNodeTexEnvironment')
    tex.image = bpy.data.images.load(args.env)
    tex.interpolation = 'Linear'
    nt.links.new(tex.outputs['Color'], bg.inputs['Color'])
    bg.inputs['Strength'].default_value = 1.0
    world.lightgroup = 'env'

    # the lettering
    lay, boxes = lettering.layout()
    face = face_material(boxes)
    bevel = principled('bevel', (1.0, 0.93, 0.80), 1.0, 0.11)
    side = principled('side', (0.42, 0.40, 0.39), 1.0, 0.26)
    name = curve_object('name', lay['name'], 0.27, 0.05, 2)
    name = to_mesh(name, [face, bevel, side])
    made_mat = principled('made', (0.9, 0.9, 0.9), 0.0, 0.5, emission=(1.0, 0.93, 0.80), strength=1.0)
    made = curve_object('made', lay['made'], 0.03, 0.0, 0)
    made = to_mesh(made, [made_mat], bevel_split=False)
    made.lightgroup = 'made'
    right, up, facing = C.block_axes()
    m = Matrix((Vector(right), Vector(up), Vector(facing))).transposed().to_4x4()
    m.translation = Vector(C.BLOCK_CENTRE)
    letters = bpy.data.collections.new('letters')
    sc.collection.children.link(letters)
    for ob in (name, made):
        ob.matrix_world = m
        letters.objects.link(ob)

    ground = terrain()

    # cards: what the metal has to reflect. They light only the letters.
    R, U, F = (np.array(v) for v in (right, up, facing))
    c0 = np.array(C.BLOCK_CENTRE)
    card('key', c0 + F * 36.0 + U * 3.0, R, U, (50.0, 38.0), (1.0, 0.94, 0.84), 1.0, 'key', letters, c0)
    # walls left and right of the block (running from behind it to in front), a ceiling and a floor
    card('rim_left', c0 - R * 15.0 - F * 5.0, F, U, (14.0, 20.0), (0.42, 0.68, 1.0), 2.2, 'rim', letters, c0)
    card('rim_right', c0 + R * 15.0 - F * 5.0, F, U, (14.0, 20.0), (1.0, 0.60, 0.24), 2.0, 'rim', letters, c0)
    card('rim_top', c0 + U * 11.0 - F * 5.0, R, F, (30.0, 14.0), (0.70, 0.84, 1.0), 1.8, 'rim', letters, c0)
    card('rim_low', c0 - U * 9.0 - F * 4.0, R, F, (30.0, 14.0), (1.0, 0.66, 0.32), 1.3, 'rim', letters, c0)
    tilt = math.radians(20.0)
    sw_y = U * math.cos(tilt) + R * math.sin(tilt)
    sw_x = R * math.cos(tilt) - U * math.sin(tilt)
    sweep = card('sweep', c0 + F * 35.0, sw_x, sw_y, (3.0, 70.0), (1.0, 0.97, 0.92), 9.0, 'sweep', letters, c0)

    # the camera, the flash beside its lens
    cam_data = bpy.data.cameras.new('camera')
    cam_data.sensor_fit = 'HORIZONTAL'
    cam_data.lens_unit = 'FOV'
    cam_data.angle = C.HFOV
    cam_data.clip_start = 0.05
    cam_data.clip_end = 20000.0
    cam = bpy.data.objects.new('camera', cam_data)
    sc.collection.objects.link(cam)
    sc.camera = cam
    cam.rotation_mode = 'QUATERNION'
    fl_data = bpy.data.lights.new('flash', 'POINT')
    fl_data.energy = 60000.0
    fl_data.shadow_soft_size = 0.03
    fl_data.color = (1.0, 0.97, 0.93)
    fl = bpy.data.objects.new('flash', fl_data)
    sc.collection.objects.link(fl)
    fl.parent = cam
    fl.location = (0.07, 0.12, 0.0)
    fl.lightgroup = 'flash'

    prev_q = None
    for f in range(-1, C.NF + 1):
        t = f / C.FPS
        pos, rgt, upv, back = C.cam_pose(t)
        rot = Matrix((Vector(rgt), Vector(upv), Vector(back))).transposed()
        q = rot.to_quaternion()
        if prev_q is not None and q.dot(prev_q) < 0:
            q.negate()
        prev_q = q.copy()
        cam.location = Vector(pos)
        cam.rotation_quaternion = q
        cam.keyframe_insert('location', frame=f)
        cam.keyframe_insert('rotation_quaternion', frame=f)
        # the sweep: a bright strip crossing what the faces mirror
        x = (t - 1.04) / 0.58
        sx = -34.0 + 68.0 * min(max(x, 0.0), 1.0)
        sweep.location = Vector(c0 + F * 35.0 + R * sx)
        sweep.keyframe_insert('location', frame=f)
    for ob in (cam, sweep):
        ad = ob.animation_data
        try:
            fcurves = ad.action.fcurves
        except AttributeError:
            fcurves = []
            for layer in ad.action.layers:
                for strip in layer.strips:
                    for bag in strip.channelbags:
                        fcurves.extend(bag.fcurves)
        for fc in fcurves:
            for kp in fc.keyframe_points:
                kp.interpolation = 'LINEAR'
    return sc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--frames', type=int, nargs=2, default=[0, C.NF - 1])
    ap.add_argument('--step', type=int, default=1)
    ap.add_argument('--list', type=int, nargs='*', default=None)
    ap.add_argument('--scale', type=int, default=100)
    ap.add_argument('--samples', type=int, default=96)
    ap.add_argument('--threads', type=int, default=4)
    ap.add_argument('--out', required=True)
    ap.add_argument('--env', required=True)
    ap.add_argument('--save-blend', default=None)
    args = ap.parse_args()
    sc = build(args)
    os.makedirs(args.out, exist_ok=True)
    if args.save_blend:
        bpy.ops.wm.save_as_mainfile(filepath=args.save_blend)
    frames = args.list if args.list else range(args.frames[0], args.frames[1] + 1, args.step)
    for f in frames:
        path = os.path.join(args.out, 'f%04d.exr' % f)
        if os.path.exists(path):
            continue
        sc.frame_set(f)
        sc.render.filepath = path
        bpy.ops.render.render(write_still=True)
        print('rendered', f, flush=True)


if __name__ == '__main__':
    main()
