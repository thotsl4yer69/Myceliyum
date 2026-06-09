"""
Mycelium Mapper — environmental layers proxy (Google Earth Engine).

Runs on Cloud Run as a service account (Application Default Credentials),
so NO service-account key file is needed at deploy time or runtime. The
Android app calls this service over HTTPS to get per-cell environmental
layers (land cover, tree-canopy %, NDVI vegetation greenness) that feed
the hotspot prediction engine.

Endpoints:
  GET  /health            → liveness probe
  POST /env-grid          → body {"points": [[lat,lng], ...]} (≤600)
                            → {"landcover": [...], "canopy": [...], "ndvi": [...]}
                            arrays are aligned 1:1 with the input points;
                            entries may be null where a layer has no value.

Security: if BACKEND_TOKEN is set, every request (except /health) must send
a matching `X-Api-Token` header. Deploy authenticated where possible; the
token is a lightweight guard against casual abuse of the public endpoint.
"""
import datetime
import os

import ee
from flask import Flask, jsonify, request

app = Flask(__name__)

_EE_READY = False
BACKEND_TOKEN = os.environ.get("BACKEND_TOKEN", "")
MAX_POINTS = 600


def ensure_ee() -> None:
    """Initialise Earth Engine once, using the runtime service account (ADC)."""
    global _EE_READY
    if not _EE_READY:
        project = os.environ.get("GCP_PROJECT") or os.environ.get("GOOGLE_CLOUD_PROJECT")
        ee.Initialize(project=project)
        _EE_READY = True


@app.before_request
def _auth():
    if request.path == "/health":
        return None
    if BACKEND_TOKEN and request.headers.get("X-Api-Token") != BACKEND_TOKEN:
        return jsonify({"error": "unauthorized"}), 401
    return None


@app.get("/health")
def health():
    return jsonify({"status": "ok"})


@app.post("/env-grid")
def env_grid():
    try:
        ensure_ee()
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": f"earth engine init failed: {exc}"}), 500

    body = request.get_json(force=True, silent=True) or {}
    points = body.get("points") or []
    if not points:
        return jsonify({"error": "no points supplied"}), 400
    if len(points) > MAX_POINTS:
        return jsonify({"error": f"too many points (max {MAX_POINTS})"}), 400

    # FeatureCollection of points (Earth Engine expects [lng, lat]).
    feats = [
        ee.Feature(ee.Geometry.Point([float(p[1]), float(p[0])]), {"idx": i})
        for i, p in enumerate(points)
    ]
    fc = ee.FeatureCollection(feats)

    # ── Layers ────────────────────────────────────────────────────────
    # ESA WorldCover 2021 — 10 m global land-cover classes.
    landcover = ee.Image("ESA/WorldCover/v200/2021").select("Map").rename("landcover")
    # Hansen Global Forest Change — continuous tree-canopy cover (%).
    canopy = (
        ee.Image("UMD/hansen/global_forest_change_2023_v1_11")
        .select("treecover2000")
        .rename("canopy")
    )
    # Recent Sentinel-2 NDVI (vegetation greenness), cloud-filtered median.
    end = datetime.date.today()
    start = end - datetime.timedelta(days=120)
    s2 = (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(fc)
        .filterDate(start.isoformat(), end.isoformat())
        .filter(ee.Filter.lt("CLOUDY_PIXEL_PERCENTAGE", 40))
        .median()
    )
    ndvi = s2.normalizedDifference(["B8", "B4"]).rename("ndvi")

    stack = landcover.addBands(canopy).addBands(ndvi)
    sampled = stack.reduceRegions(
        collection=fc, reducer=ee.Reducer.first(), scale=30
    )

    try:
        features = sampled.getInfo()["features"]
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": f"earth engine compute failed: {exc}"}), 502

    by_idx = {f["properties"].get("idx"): f["properties"] for f in features}
    n = len(points)

    def col(name):
        return [by_idx.get(i, {}).get(name) for i in range(n)]

    return jsonify(
        {
            "landcover": col("landcover"),
            "canopy": col("canopy"),
            "ndvi": col("ndvi"),
        }
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8080)))
