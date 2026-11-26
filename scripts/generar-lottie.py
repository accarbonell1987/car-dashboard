#!/usr/bin/env python3
"""Genera las animaciones Lottie del asistente de mantenimiento (screen/src/main/assets/lottie).

Son propias, en estilo plano, con el celeste del tablero. Para cambiarlas por otras de LottieFiles basta con
reemplazar el JSON con el mismo nombre.
"""
import json, os

CELESTE = [0.31, 0.71, 0.98, 1]
VERDE = [0.30, 0.80, 0.45, 1]
FR, W, H = 60, 120, 120
SALIDA = os.path.join(os.path.dirname(__file__), "..", "screen", "src", "main", "assets", "lottie")

def fijo(v): return {"a": 0, "k": v}
def anim(claves): return {"a": 1, "k": [{"t": t, "s": s if isinstance(s, list) else [s], "i": {"x": [0.4], "y": [1]}, "o": {"x": [0.6], "y": [0]}} for t, s in claves]}
def tr(pos=(0, 0), op=fijo(100), esc=fijo([100, 100]), rot=fijo(0)):
    return {"ty": "tr", "p": fijo(list(pos)), "a": fijo([0, 0]), "s": esc, "r": rot, "o": op, "sk": fijo(0), "sa": fijo(0)}
def trazo(color, ancho): return {"ty": "st", "c": fijo(color), "o": fijo(100), "w": fijo(ancho), "lc": 2, "lj": 2}
def relleno(color): return {"ty": "fl", "c": fijo(color), "o": fijo(100), "r": 1}
def capa(nombre, formas, op=60):
    return {"ty": 4, "nm": nombre, "ind": 1, "ip": 0, "op": op, "st": 0, "sr": 1,
            "ks": {"o": fijo(100), "r": fijo(0), "p": fijo([W / 2, H / 2, 0]), "a": fijo([0, 0, 0]), "s": fijo([100, 100, 100])},
            "shapes": formas}
def archivo(nombre, capas, op=60):
    return {"v": "5.7.4", "fr": FR, "ip": 0, "op": op, "w": W, "h": H, "nm": nombre, "ddd": 0, "assets": [],
            "layers": [dict(c, ind=i + 1) for i, c in enumerate(capas)]}
def ruta(puntos):
    n = len(puntos)
    return {"ty": "sh", "ks": fijo({"c": False, "v": puntos, "i": [[0, 0]] * n, "o": [[0, 0]] * n})}

# Tres puntos que suben y bajan: el asistente está trabajando contigo.
def progreso():
    grupos = []
    for i, x in enumerate((-30, 0, 30)):
        d = i * 8
        y = anim([(d, [x, 8]), (d + 12, [x, -10]), (d + 24, [x, 8]), (60, [x, 8])])
        grupos.append({"ty": "gr", "it": [{"ty": "el", "p": fijo([0, 0]), "s": fijo([18, 18])}, relleno(CELESTE),
                                          dict(tr(), p=y)]})
    return archivo("progreso", [capa("puntos", grupos)])

# Un círculo que se dibuja y una tilde: el plan quedó listo.
def listo():
    circulo = {"ty": "gr", "it": [{"ty": "el", "p": fijo([0, 0]), "s": fijo([88, 88])}, trazo(VERDE, 7),
                                  {"ty": "tm", "s": fijo(0), "e": anim([(0, 0), (24, 100)]), "o": fijo(0), "m": 1}, tr()]}
    tilde = {"ty": "gr", "it": [ruta([[-20, 2], [-6, 16], [22, -14]]), trazo(VERDE, 8),
                                {"ty": "tm", "s": fijo(0), "e": anim([(0, 0), (20, 0), (40, 100)]), "o": fijo(0), "m": 1}, tr()]}
    return archivo("listo", [capa("tilde", [tilde]), capa("circulo", [circulo])], op=90)

# Dos barras que respiran: esperar a que el auto se detenga.
def pausa():
    barras = []
    for x in (-16, 16):
        barras.append({"ty": "gr", "it": [{"ty": "rc", "p": fijo([x, 0]), "s": fijo([14, 52]), "r": fijo(5)}, relleno(CELESTE), tr()]})
    grupo = {"ty": "gr", "it": barras + [tr(op=anim([(0, 100), (45, 35), (90, 100)]))]}
    return archivo("pausa", [capa("barras", [grupo], op=90)], op=90)

os.makedirs(SALIDA, exist_ok=True)
for nombre, datos in (("progreso", progreso()), ("listo", listo()), ("pausa", pausa())):
    with open(os.path.join(SALIDA, nombre + ".json"), "w") as f:
        json.dump(datos, f, separators=(",", ":"))
    print(nombre, os.path.getsize(os.path.join(SALIDA, nombre + ".json")), "bytes")
