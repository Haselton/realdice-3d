extends Node3D

const RUN_LEN := 1200.0
const TERRAIN_W := 150.0
const ROWS := 241
const COLS := 41
const CELL_Z := RUN_LEN / float(ROWS - 1)
const CELL_X := TERRAIN_W / float(COLS - 1)

var rider: CharacterBody3D
var board: MeshInstance3D
var rider_visual: Node3D
var cam: Camera3D
var ground_ray: RayCast3D
var hud: CanvasLayer
var speed_label: Label
var info_label: Label
var progress_bar: ProgressBar
var status_label: Label
var jump_button: Button
var grind_button: Button
var reset_button: Button

var neutral_gravity := Vector3.ZERO
var filtered_gravity := Vector3.ZERO
var steer := 0.0
var pitch := 0.0
var calibration_frames := 0
var run_time := 0.0
var distance := 0.0
var finished := false
var jump_lock := 0.0
var last_accel_mag := 0.0
var visual_roll := 0.0
var visual_pitch := 0.0
var rng := RandomNumberGenerator.new()
var hazards: Array[Node3D] = []

func _ready():
    rng.seed = 122193
    _build_environment()
    _build_terrain()
    _build_rider()
    _build_hazards()
    _build_hud()
    filtered_gravity = Input.get_gravity()
    neutral_gravity = filtered_gravity
    calibration_frames = 0

func _build_environment():
    var env = WorldEnvironment.new()
    var e = Environment.new()
    e.background_mode = Environment.BG_SKY
    var sky = Sky.new()
    var mat = ProceduralSkyMaterial.new()
    mat.sky_top_color = Color(0.08,0.18,0.28)
    mat.sky_horizon_color = Color(0.56,0.73,0.84)
    mat.ground_bottom_color = Color(0.18,0.23,0.26)
    mat.ground_horizon_color = Color(0.56,0.73,0.84)
    sky.sky_material = mat
    e.sky = sky
    e.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
    e.ambient_light_energy = 0.65
    e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
    env.environment = e
    add_child(env)

    var sun = DirectionalLight3D.new()
    sun.rotation_degrees = Vector3(-48,-28,0)
    sun.light_energy = 1.25
    sun.shadow_enabled = true
    add_child(sun)

func terrain_height(x: float, z: float) -> float:
    var t = clamp(-z / RUN_LEN, 0.0, 1.0)
    var slope = 70.0 - t * 125.0
    var ridge = 7.5 * sin(t * 13.0 + x * 0.025) + 3.5 * sin(t * 29.0 - x * 0.05)
    var side = 0.0028 * x * x
    var rollers = 2.0 * sin(t * 55.0) + 1.1 * sin(t * 91.0 + x * 0.08)
    return slope + ridge + side + rollers

func _build_terrain():
    var st = SurfaceTool.new()
    st.begin(Mesh.PRIMITIVE_TRIANGLES)
    var mat = StandardMaterial3D.new()
    mat.albedo_color = Color(0.89,0.94,0.97)
    mat.roughness = 0.88

    var verts: Array[Vector3] = []
    for r in range(ROWS):
        var z = -float(r) * CELL_Z
        for c in range(COLS):
            var x = -TERRAIN_W*0.5 + float(c)*CELL_X
            verts.append(Vector3(x, terrain_height(x,z), z))

    for r in range(ROWS-1):
        for c in range(COLS-1):
            var i = r*COLS+c
            var a=verts[i]
            var b=verts[i+1]
            var d=verts[i+COLS]
            var e=verts[i+COLS+1]
            var n1 = Plane(a,d,b).normal
            st.set_normal(n1); st.add_vertex(a)
            st.set_normal(n1); st.add_vertex(d)
            st.set_normal(n1); st.add_vertex(b)
            var n2 = Plane(b,d,e).normal
            st.set_normal(n2); st.add_vertex(b)
            st.set_normal(n2); st.add_vertex(d)
            st.set_normal(n2); st.add_vertex(e)
    st.generate_normals()
    var mesh = st.commit()
    var mi = MeshInstance3D.new()
    mi.mesh = mesh
    mi.material_override = mat
    add_child(mi)

    var body = StaticBody3D.new()
    var shape = ConcavePolygonShape3D.new()
    shape.data = mesh.get_faces()
    var cs = CollisionShape3D.new()
    cs.shape = shape
    body.add_child(cs)
    add_child(body)

    var mountain = MeshInstance3D.new()
    var cm = CylinderMesh.new()
    cm.top_radius = 0.0
    cm.bottom_radius = 260.0
    cm.height = 240.0
    cm.radial_segments = 64
    cm.rings = 12
    mountain.mesh = cm
    mountain.position = Vector3(0,90,-1450)
    mountain.scale = Vector3(1.15,1.0,0.9)
    var mm = StandardMaterial3D.new()
    mm.albedo_color = Color(0.78,0.84,0.88)
    mm.roughness = 1.0
    mountain.material_override = mm
    add_child(mountain)

func _mesh_instance(mesh: Mesh, color: Color) -> MeshInstance3D:
    var mi = MeshInstance3D.new()
    mi.mesh = mesh
    var m = StandardMaterial3D.new()
    m.albedo_color = color
    m.roughness = 0.62
    mi.material_override = m
    return mi

func _build_rider():
    rider = CharacterBody3D.new()
    rider.position = Vector3(0, terrain_height(0,0)+1.5, -3)
    rider.floor_max_angle = deg_to_rad(72)
    rider.floor_snap_length = 1.1
    add_child(rider)

    var capsule = CapsuleShape3D.new()
    capsule.radius = 0.45
    capsule.height = 1.8
    var col = CollisionShape3D.new()
    col.shape = capsule
    col.position.y = 1.0
    rider.add_child(col)

    ground_ray = RayCast3D.new()
    ground_ray.target_position = Vector3(0,-2.3,0)
    ground_ray.enabled = true
    rider.add_child(ground_ray)

    rider_visual = Node3D.new()
    rider.add_child(rider_visual)

    board = _mesh_instance(BoxMesh.new(), Color(0.06,0.05,0.09))
    board.mesh.size = Vector3(0.32,0.05,1.85)
    board.position = Vector3(0,0.13,0)
    rider_visual.add_child(board)
    for j in range(4):
        var strip = _mesh_instance(BoxMesh.new(), [Color(0.49,0.17,0.72),Color(0.05,0.65,0.78),Color(0.9,0.33,0.08),Color(0.92,0.92,0.92)][j])
        strip.mesh.size = Vector3(0.34,0.012,0.16)
        strip.position = Vector3(0,0.17,-0.55 + j*0.35)
        rider_visual.add_child(strip)

    for sx in [-1.0,1.0]:
        var legmesh = CapsuleMesh.new()
        legmesh.radius = 0.17
        legmesh.height = 0.86
        legmesh.radial_segments = 20
        legmesh.rings = 6
        var leg = _mesh_instance(legmesh, Color(0.25,0.29,0.23))
        leg.position = Vector3(0.16*sx,0.72,0)
        leg.rotation_degrees.x = 13*sx
        rider_visual.add_child(leg)
        var boot = _mesh_instance(BoxMesh.new(), Color(0.22,0.08,0.31))
        boot.mesh.size = Vector3(0.34,0.20,0.48)
        boot.position = Vector3(0.18*sx,0.28,0.10*sx)
        rider_visual.add_child(boot)

    var torso_mesh = CapsuleMesh.new()
    torso_mesh.radius = 0.43
    torso_mesh.height = 0.95
    torso_mesh.radial_segments = 28
    torso_mesh.rings = 8
    var torso = _mesh_instance(torso_mesh, Color(0.035,0.045,0.06))
    torso.position = Vector3(0,1.45,0)
    torso.scale = Vector3(1.15,1.0,0.88)
    rider_visual.add_child(torso)

    for item in [
        [Vector3(-0.36,1.48,-0.33), Color(0.55,0.18,0.75)],
        [Vector3(0.34,1.36,-0.33), Color(0.02,0.66,0.8)],
        [Vector3(-0.10,1.65,-0.40), Color(0.9,0.28,0.05)]
    ]:
        var patch = _mesh_instance(BoxMesh.new(), item[1])
        patch.mesh.size = Vector3(0.28,0.18,0.025)
        patch.position = item[0]
        rider_visual.add_child(patch)

    for sx in [-1.0,1.0]:
        var arm_mesh = CapsuleMesh.new()
        arm_mesh.radius = 0.13
        arm_mesh.height = 0.76
        arm_mesh.radial_segments = 16
        var arm = _mesh_instance(arm_mesh, Color(0.03,0.04,0.05))
        arm.position = Vector3(0.46*sx,1.42,-0.02)
        arm.rotation_degrees.z = -22*sx
        rider_visual.add_child(arm)

    var head_mesh = SphereMesh.new()
    head_mesh.radius = 0.23
    head_mesh.height = 0.46
    head_mesh.radial_segments = 24
    head_mesh.rings = 12
    var head = _mesh_instance(head_mesh, Color(0.35,0.18,0.10))
    head.position = Vector3(0,2.12,0)
    rider_visual.add_child(head)
    var cap_mesh = CylinderMesh.new()
    cap_mesh.top_radius = 0.25
    cap_mesh.bottom_radius = 0.27
    cap_mesh.height = 0.14
    cap_mesh.radial_segments = 24
    var cap = _mesh_instance(cap_mesh, Color(0.02,0.025,0.03))
    cap.position = Vector3(0,2.31,0)
    rider_visual.add_child(cap)

    var chain_mesh = TorusMesh.new()
    chain_mesh.inner_radius = 0.14
    chain_mesh.outer_radius = 0.18
    chain_mesh.rings = 24
    chain_mesh.ring_segments = 8
    var chain = _mesh_instance(chain_mesh, Color(0.85,0.56,0.08))
    chain.position = Vector3(0,1.85,-0.36)
    chain.rotation_degrees.x = 90
    chain.scale = Vector3(1.0,1.25,1.0)
    rider_visual.add_child(chain)

    cam = Camera3D.new()
    cam.position = Vector3(0,2.4,6.5)
    cam.fov = 72
    rider.add_child(cam)
    cam.look_at_from_position(cam.position, Vector3(0,1.1,-7), Vector3.UP)

func _build_hazards():
    for i in range(95):
        var z = -40.0 - i * 11.5 - rng.randf_range(0,6)
        var x = rng.randf_range(-55,55)
        if abs(x) < 5.5 and rng.randf() < 0.45:
            x += 10.0 * sign(x if x != 0 else 1.0)
        var node = Node3D.new()
        node.position = Vector3(x,terrain_height(x,z),z)
        var r = rng.randf()
        if r < 0.55:
            _add_tree(node)
        elif r < 0.85:
            _add_rock(node)
        else:
            _add_ice(node)
        add_child(node)
        hazards.append(node)

    var bear = Node3D.new()
    bear.name = "BearEncounter"
    bear.position = Vector3(-8, terrain_height(-8,-620), -620)
    _add_bear(bear)
    add_child(bear)
    hazards.append(bear)

func _add_tree(n: Node3D):
    var trunk = _mesh_instance(CylinderMesh.new(),Color(0.21,0.14,0.09))
    trunk.mesh.top_radius=0.12; trunk.mesh.bottom_radius=0.18; trunk.mesh.height=2.2
    trunk.position.y=1.1
    n.add_child(trunk)
    for j in range(3):
        var cm=CylinderMesh.new()
        cm.top_radius=0.0; cm.bottom_radius=1.0-0.17*j; cm.height=2.2-0.25*j; cm.radial_segments=12
        var crown=_mesh_instance(cm,Color(0.04,0.22,0.17))
        crown.position.y=2.0+0.65*j
        n.add_child(crown)

func _add_rock(n: Node3D):
    var sm=SphereMesh.new()
    sm.radius=0.8; sm.height=1.3; sm.radial_segments=12; sm.rings=7
    var r=_mesh_instance(sm,Color(0.22,0.25,0.27))
    r.scale=Vector3(rng.randf_range(0.7,1.4),rng.randf_range(0.5,1.0),rng.randf_range(0.7,1.3))
    r.position.y=0.45
    n.add_child(r)

func _add_ice(n: Node3D):
    var b=_mesh_instance(BoxMesh.new(),Color(0.18,0.58,0.72))
    b.mesh.size=Vector3(3.6,0.04,3.0)
    b.position.y=0.05
    n.add_child(b)

func _add_bear(n: Node3D):
    var body_mesh=SphereMesh.new(); body_mesh.radius=0.7; body_mesh.height=1.1; body_mesh.radial_segments=18
    var body=_mesh_instance(body_mesh,Color(0.20,0.10,0.05))
    body.position=Vector3(0,0.75,0); body.scale=Vector3(1.45,1.0,1.0)
    n.add_child(body)
    var head_mesh=SphereMesh.new(); head_mesh.radius=0.42; head_mesh.height=0.8; head_mesh.radial_segments=18
    var head=_mesh_instance(head_mesh,Color(0.16,0.08,0.04)); head.position=Vector3(0,1.0,-0.75); n.add_child(head)

func _build_hud():
    hud=CanvasLayer.new(); add_child(hud)
    var margin=MarginContainer.new()
    margin.add_theme_constant_override("margin_left",26); margin.add_theme_constant_override("margin_top",22)
    margin.add_theme_constant_override("margin_right",26)
    hud.add_child(margin)
    var v=VBoxContainer.new(); margin.add_child(v)
    var title=Label.new(); title.text="FROST LINE: SHASTA"; title.add_theme_font_size_override("font_size",30); v.add_child(title)
    info_label=Label.new(); info_label.text="SHORT SUMMIT RUN • PHYSICS TEST"; info_label.add_theme_font_size_override("font_size",18); v.add_child(info_label)
    speed_label=Label.new(); speed_label.add_theme_font_size_override("font_size",24); v.add_child(speed_label)
    progress_bar=ProgressBar.new(); progress_bar.min_value=0;progress_bar.max_value=RUN_LEN;progress_bar.custom_minimum_size=Vector2(430,18);v.add_child(progress_bar)
    status_label=Label.new(); status_label.add_theme_font_size_override("font_size",18); v.add_child(status_label)

    jump_button=Button.new(); jump_button.text="JUMP"; jump_button.custom_minimum_size=Vector2(150,72)
    jump_button.position=Vector2(1730,970); hud.add_child(jump_button); jump_button.pressed.connect(_jump)
    grind_button=Button.new(); grind_button.text="GRIND"; grind_button.custom_minimum_size=Vector2(150,72)
    grind_button.position=Vector2(1560,970); hud.add_child(grind_button)
    reset_button=Button.new(); reset_button.text="CAM RESET"; reset_button.custom_minimum_size=Vector2(180,72)
    reset_button.position=Vector2(24,970); hud.add_child(reset_button); reset_button.pressed.connect(_calibrate)

func _calibrate():
    neutral_gravity=Input.get_gravity()
    calibration_frames=0
    status_label.text="Calibrating… hold phone neutral"

func _jump():
    if rider.is_on_floor() and jump_lock<=0.0:
        rider.velocity.y += 8.0
        jump_lock=0.45

func _physics_process(delta):
    if finished: return
    run_time += delta
    jump_lock=max(0.0,jump_lock-delta)

    var g=Input.get_gravity()
    if g.length()>0.1:
        filtered_gravity=filtered_gravity.lerp(g,0.16)
        if calibration_frames<20:
            neutral_gravity=neutral_gravity.lerp(filtered_gravity,0.12)
            calibration_frames+=1

        var roll_now=atan2(filtered_gravity.x,-filtered_gravity.y)
        var roll_neutral=atan2(neutral_gravity.x,-neutral_gravity.y)
        var pitch_now=asin(clamp(filtered_gravity.z/9.81,-1.0,1.0))
        var pitch_neutral=asin(clamp(neutral_gravity.z/9.81,-1.0,1.0))
        steer=_response(rad_to_deg(roll_now-roll_neutral),3.0,24.0)
        pitch=_response(-rad_to_deg(pitch_now-pitch_neutral),3.0,18.0)

    var acc=Input.get_accelerometer()
    var amag=acc.length()
    if last_accel_mag>0.1 and abs(amag-last_accel_mag)>6.0 and jump_lock<=0.0:
        _jump()
    last_accel_mag=lerp(last_accel_mag,amag,0.18)

    var floor_normal=Vector3.UP
    if ground_ray.is_colliding():
        floor_normal=ground_ray.get_collision_normal().normalized()

    var forward=(-rider.global_transform.basis.z).slide(floor_normal).normalized()
    if forward.length()<0.1: forward=Vector3(0,0,-1)
    var tangent_gravity=Vector3.DOWN.slide(floor_normal).normalized()
    if tangent_gravity.length()>0.1:
        rider.velocity += tangent_gravity*18.0*delta

    var speed=rider.velocity.length()
    var turn_rate=deg_to_rad(26.0+48.0*clamp(speed/32.0,0,1))*steer
    var target_forward=forward.rotated(floor_normal,turn_rate*delta)
    var vf=target_forward*rider.velocity.dot(target_forward)
    var lateral=rider.velocity-vf
    var grip=0.82*(0.55+0.45*abs(steer))
    lateral=lateral.lerp(Vector3.ZERO,clamp(grip*7.0*delta,0,1))
    rider.velocity=vf+lateral

    var target_speed=22.0+max(pitch,0.0)*18.0
    if pitch<0:
        rider.velocity=rider.velocity.move_toward(Vector3.ZERO,(-pitch)*22.0*delta)
    elif rider.velocity.length()<target_speed:
        rider.velocity += target_forward*(5.5+8.5*pitch)*delta

    if not rider.is_on_floor():
        rider.velocity += Vector3.DOWN*18.0*delta

    if rider.velocity.length()>42.0:
        rider.velocity=rider.velocity.normalized()*42.0

    rider.move_and_slide()
    distance=clamp(-rider.position.z,0.0,RUN_LEN)

    visual_roll=lerp(visual_roll,-steer*0.48,clamp(delta*6.0,0,1))
    visual_pitch=lerp(visual_pitch,pitch*0.14,clamp(delta*5.0,0,1))
    rider_visual.rotation.z=visual_roll
    rider_visual.rotation.x=visual_pitch

    var planar=Vector3(rider.velocity.x,0,rider.velocity.z)
    if planar.length()>1:
        var yaw=atan2(-planar.x,-planar.z)
        rider.rotation.y=lerp_angle(rider.rotation.y,yaw,clamp(delta*4.5,0,1))

    cam.fov=lerp(cam.fov,68.0+clamp(speed/42.0,0,1)*10.0,clamp(delta*2.0,0,1))

    var bear=get_node_or_null("BearEncounter")
    if bear and distance>530 and distance<690:
        bear.position.x=lerp(bear.position.x,10.0,delta*0.32)

    speed_label.text="SPEED %d km/h" % int(speed*3.6)
    progress_bar.value=distance
    status_label.text="Tilt to carve • lean forward/back • flick or tap JUMP"

    if distance>=RUN_LEN-8:
        finished=true
        status_label.text="BASE CHECKPOINT REACHED • %.1f s" % run_time

func _response(v: float,dead: float,full: float)->float:
    var a=abs(v)
    if a<=dead: return 0.0
    var t=clamp((a-dead)/(full-dead),0.0,1.0)
    t=t*t*(3.0-2.0*t)
    return sign(v)*t
