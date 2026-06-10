/* ============================================================
   MYCELIYUM — HYPHAE AMBIENT LAYER (site background)
   The CH-01 wallpaper shader, tuned down to live behind the
   page: mycelial veins lean toward the cursor and ignite;
   clicks send nutrient pulses through the lattice.
   Self-contained — injects its own canvas, no markup needed.
   ============================================================ */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  var canvas = document.createElement('canvas');
  canvas.id = 'hyphae-bg';
  canvas.setAttribute('aria-hidden', 'true');
  canvas.style.cssText =
    'position:fixed;inset:0;width:100%;height:100%;z-index:0;' +
    'pointer-events:none;display:block;';
  document.body.insertBefore(canvas, document.body.firstChild);

  var gl = canvas.getContext('webgl', { antialias: false, alpha: false, powerPreference: 'low-power' });
  if (!gl) { canvas.remove(); return; }

  var VERT = [
    'attribute vec2 a_pos;',
    'void main(){ gl_Position = vec4(a_pos, 0.0, 1.0); }'
  ].join('\n');

  var FRAG = [
    'precision highp float;',
    'uniform vec2  u_res;',
    'uniform float u_time;',
    'uniform vec2  u_mouse;',
    'uniform vec3  u_clicks[10];',
    '#define BG    vec3(0.043, 0.051, 0.043)',
    '#define SIG   vec3(0.329, 0.878, 0.627)',
    '#define AMBER vec3(0.902, 0.698, 0.298)',
    '#define BONE  vec3(0.843, 0.851, 0.796)',
    'float hash21(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p+45.32); return fract(p.x*p.y); }',
    'float vnoise(vec2 p){',
    '  vec2 i = floor(p), f = fract(p);',
    '  f = f*f*(3.0-2.0*f);',
    '  float a = hash21(i), b = hash21(i+vec2(1.0,0.0)), c = hash21(i+vec2(0.0,1.0)), d = hash21(i+vec2(1.0,1.0));',
    '  return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);',
    '}',
    'float fbm(vec2 p){',
    '  float v = 0.0, a = 0.5;',
    '  for(int i=0;i<5;i++){ v += a*vnoise(p); p = p*2.03 + vec2(1.7, 9.2); a *= 0.5; }',
    '  return v;',
    '}',
    'vec2 toUV(vec2 px){ return (px - 0.5*u_res)/u_res.y; }',
    'void main(){',
    '  vec2 uv = toUV(gl_FragCoord.xy);',
    '  vec2 m  = toUV(u_mouse);',
    '  float t = u_time*0.05;',
    '  float dm    = length(uv - m);',
    '  float torch = exp(-dm*dm*6.0);',
    '  vec2 p = uv + (m - uv)*torch*0.35;',
    '  vec2 q = vec2(fbm(p*2.1 + vec2(t, -t*0.7)), fbm(p*2.1 + vec2(5.2 - t*0.8, 1.3 + t)));',
    '  vec2 w = p*3.0 + (q - 0.5)*3.4;',
    '  float n1 = fbm(w);',
    '  float n2 = fbm(w*2.3 + vec2(7.7, 2.2));',
    '  float v1 = pow(clamp(1.0 - abs(n1 - 0.5)*2.2, 0.0, 1.0), 10.0);',
    '  float v2 = pow(clamp(1.0 - abs(n2 - 0.5)*2.4, 0.0, 1.0), 14.0);',
    '  float pulse = 0.0;',
    '  for(int i=0;i<10;i++){',
    '    vec3 c = u_clicks[i];',
    '    float age = u_time - c.z;',
    '    if(c.z < -50.0 || age > 7.0) continue;',
    '    float r = length(uv - toUV(c.xy));',
    '    pulse += exp(-pow((r - age*0.30)*9.0, 2.0)) * exp(-age*0.7);',
    '  }',
    '  float amb = 0.05 + 0.025*sin(t*3.0 + n1*8.0);',
    '  float b1 = v1*(amb + 0.55*torch + 0.85*pulse);',
    '  float b2 = v2*(amb*0.6 + 0.40*torch + 0.60*pulse);',
    '  vec3 col = BG;',
    '  col += BONE*v1*0.030;',
    '  col += SIG*(b1 + b2)*0.8;',
    '  col += AMBER*pulse*v2*0.25;',
    '  col *= 1.0 - 0.30*dot(uv,uv);',
    '  gl_FragColor = vec4(col, 1.0);',
    '}'
  ].join('\n');

  function compile(type, src) {
    var sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      console.error('hyphae-bg:', gl.getShaderInfoLog(sh));
      return null;
    }
    return sh;
  }

  var vs = compile(gl.VERTEX_SHADER, VERT);
  var fs = compile(gl.FRAGMENT_SHADER, FRAG);
  if (!vs || !fs) { canvas.remove(); return; }

  var prog = gl.createProgram();
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { canvas.remove(); return; }
  gl.useProgram(prog);

  var buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  var aPos = gl.getAttribLocation(prog, 'a_pos');
  gl.enableVertexAttribArray(aPos);
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

  var U = {
    res:    gl.getUniformLocation(prog, 'u_res'),
    time:   gl.getUniformLocation(prog, 'u_time'),
    mouse:  gl.getUniformLocation(prog, 'u_mouse'),
    clicks: gl.getUniformLocation(prog, 'u_clicks')
  };

  var dpr = 1;
  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 1.5);
    canvas.width = Math.round(window.innerWidth * dpr);
    canvas.height = Math.round(window.innerHeight * dpr);
    gl.viewport(0, 0, canvas.width, canvas.height);
  }
  window.addEventListener('resize', resize);
  resize();

  /* ---- input ---- */
  var tx = window.innerWidth / 2, ty = window.innerHeight * 0.35;
  var mx = tx, my = ty;
  var clicks = new Float32Array(30);
  for (var i = 0; i < 10; i++) clicks[i * 3 + 2] = -100;
  var clickIdx = 0;
  var t0 = performance.now() / 1000;

  document.addEventListener('pointermove', function (e) {
    tx = e.clientX; ty = e.clientY;
  }, { passive: true });

  document.addEventListener('pointerdown', function (e) {
    clicks[clickIdx * 3]     = e.clientX;
    clicks[clickIdx * 3 + 1] = e.clientY;
    clicks[clickIdx * 3 + 2] = performance.now() / 1000 - t0;
    clickIdx = (clickIdx + 1) % 10;
  }, { passive: true });

  /* ---- render loop ---- */
  var frozen = reduceMotion;
  var lastT = 0;
  var c = new Float32Array(30);

  function frame() {
    requestAnimationFrame(frame);
    if (document.hidden) return;

    var t = frozen ? lastT : (performance.now() / 1000 - t0);
    lastT = t;

    mx += (tx - mx) * 0.10;
    my += (ty - my) * 0.10;

    gl.uniform2f(U.res, canvas.width, canvas.height);
    gl.uniform1f(U.time, t);
    gl.uniform2f(U.mouse, mx * dpr, canvas.height - my * dpr);

    for (var j = 0; j < 10; j++) {
      c[j * 3]     = clicks[j * 3] * dpr;
      c[j * 3 + 1] = canvas.height - clicks[j * 3 + 1] * dpr;
      c[j * 3 + 2] = clicks[j * 3 + 2];
    }
    gl.uniform3fv(U.clicks, c);

    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  requestAnimationFrame(frame);
})();
