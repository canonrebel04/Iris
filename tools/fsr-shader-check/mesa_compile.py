import moderngl, glob, sys, os
ctx = moderngl.create_standalone_context(backend='egl', require=450)
print(ctx.info['GL_RENDERER'], ctx.info['GL_VERSION'])
bad = 0
for f in sorted(glob.glob('gl/*.frag')) + sorted(glob.glob('gl/*.comp')):
    try:
        if f.endswith('.comp'):
            ctx.compute_shader(open(f).read())
        else:
            ctx.program(vertex_shader=open(f[:-5] + '.vert').read(), fragment_shader=open(f).read())
        print('OK  ', f)
    except Exception as e:
        bad += 1
        print('FAIL', f, '\n', str(e)[:2000])
sys.exit(bad)
