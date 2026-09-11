import bpy, math, os
OUT=os.environ.get('REALCOIN_OBJ','RealCoin3D/app/src/main/assets/quarter_high.obj')
os.makedirs(os.path.dirname(OUT),exist_ok=True)
bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)

bpy.ops.mesh.primitive_cylinder_add(vertices=256,radius=1.0,depth=0.18,location=(0,0,0))
coin=bpy.context.object; coin.name='CoinBlank'
bev=coin.modifiers.new('MintedBevel','BEVEL'); bev.width=0.045; bev.segments=5
bpy.context.view_layer.objects.active=coin; bpy.ops.object.modifier_apply(modifier=bev.name); bpy.ops.object.shade_smooth()

for z in (0.101,-0.101):
    bpy.ops.mesh.primitive_torus_add(major_radius=0.86,minor_radius=0.025,major_segments=192,minor_segments=12,location=(0,0,z)); bpy.ops.object.shade_smooth()
for z in (0.099,-0.099):
    for rr in (0.75,0.68):
        bpy.ops.mesh.primitive_torus_add(major_radius=rr,minor_radius=0.008,major_segments=160,minor_segments=8,location=(0,0,z)); bpy.ops.object.shade_smooth()
for i in range(128):
    a=2*math.pi*i/128.0
    bpy.ops.mesh.primitive_cube_add(size=1,location=(1.012*math.cos(a),1.012*math.sin(a),0),rotation=(0,0,a))
    o=bpy.context.object; o.dimensions=(0.035,0.022,0.16); bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)

def add_text(body,z,size,extrude,back=False,y=0):
    bpy.ops.object.text_add(location=(0,y,z)); t=bpy.context.object; t.data.body=body; t.data.align_x='CENTER'; t.data.align_y='CENTER'; t.data.size=size; t.data.extrude=extrude; t.data.bevel_depth=0.003; t.data.bevel_resolution=2
    if back: t.rotation_euler=(math.pi,0,math.pi)
    bpy.context.view_layer.objects.active=t; bpy.ops.object.convert(target='MESH'); bpy.ops.object.shade_smooth()

add_text('LIBERTY',0.105,0.19,0.012,False,0.35)
add_text('QUARTER DOLLAR',0.105,0.075,0.010,False,-0.58)
add_text('2026',0.105,0.085,0.010,False,-0.42)
add_text('UNITED STATES',-0.105,0.075,0.010,True,-0.58)
add_text('E PLURIBUS UNUM',-0.105,0.055,0.008,True,0.55)

bpy.ops.mesh.primitive_circle_add(vertices=10,radius=0.27,fill_type='NGON',location=(0,0,0.108),rotation=(0,0,math.pi/2))
star=bpy.context.object
for idx,v in enumerate(star.data.vertices):
    rr=0.27 if idx%2==0 else 0.12; ang=2*math.pi*idx/10+math.pi/2; v.co.x=rr*math.cos(ang); v.co.y=rr*math.sin(ang)
sol=star.modifiers.new('Relief','SOLIDIFY'); sol.thickness=0.018
bev=star.modifiers.new('Edge','BEVEL'); bev.width=0.008; bev.segments=3
for m in list(star.modifiers): bpy.context.view_layer.objects.active=star; bpy.ops.object.modifier_apply(modifier=m.name)

for loc,scale,rot in [((0,0,-0.11),(0.18,0.26,0.018),0),((-0.34,0.10,-0.11),(0.34,0.13,0.014),-0.35),((0.34,0.10,-0.11),(0.34,0.13,0.014),0.35)]:
    bpy.ops.mesh.primitive_uv_sphere_add(segments=48,ring_count=24,location=loc); o=bpy.context.object; o.scale=scale; o.rotation_euler[2]=rot; bpy.ops.object.transform_apply(location=False,rotation=False,scale=True); bpy.ops.object.shade_smooth()

deps=bpy.context.evaluated_depsgraph_get()
with open(OUT,'w',encoding='utf-8') as f:
    f.write('# RealCoin Blender generated high-detail coin\n'); voff=noff=0
    for obj in [o for o in bpy.context.scene.objects if o.type=='MESH']:
        ev=obj.evaluated_get(deps); mesh=ev.to_mesh(); mesh.calc_loop_triangles(); M=ev.matrix_world; N=M.to_3x3().inverted().transposed(); f.write('o %s\n'%obj.name)
        for v in mesh.vertices:
            p=M@v.co; f.write('v %.7f %.7f %.7f\n'%(p.x,p.y,p.z))
        loopmap={}; normals=[]
        for li,loop in enumerate(mesh.loops):
            n=(N@loop.normal).normalized(); loopmap[li]=len(normals)+1+noff; normals.append(n)
        for n in normals: f.write('vn %.7f %.7f %.7f\n'%(n.x,n.y,n.z))
        for tri in mesh.loop_triangles:
            parts=[]
            for li,vi in zip(tri.loops,tri.vertices): parts.append('%d//%d'%(vi+1+voff,loopmap[li]))
            f.write('f '+' '.join(parts)+'\n')
        voff+=len(mesh.vertices); noff+=len(normals); ev.to_mesh_clear()
print('WROTE',OUT)
