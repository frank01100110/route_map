from __future__ import annotations

from typing import Any

import requests
from dash import ALL, Dash, Input, Output, State, dcc, html, ctx


NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
OSRM_ROUTE_URL = "https://router.project-osrm.org/route/v1/driving"
HTTP_HEADERS = {
    "User-Agent": "taxi-map-dashboard/1.0 (local development contact: local-app)"
}


app = Dash(
    __name__,
    external_stylesheets=[
        "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
    ],
    external_scripts=[
        "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
    ],
    title="Taxi Map Dashboard",
)

app.layout = html.Div(
    className="app-shell",
    children=[
        dcc.Store(id="stops-store", data=[{"id": 0}]),
        dcc.Store(id="route-data"),
        dcc.Store(id="route-summary"),
        html.Div(id="map-render-signal", style={"display": "none"}),
        html.Section(
            className="control-panel",
            children=[
                html.Div(
                    className="panel-header",
                    children=[
                        html.P("Taxi Planner", className="eyebrow"),
                        html.H1("Dashboard percorsi e tariffe"),
                        html.P(
                            "Usa OpenStreetMap per la mappa e OSRM per il routing. "
                            "Inserisci partenza, tappe intermedie e destinazione finale "
                            "per ottenere il percorso e la tariffa stimata.",
                            className="intro",
                        ),
                    ],
                ),
                html.Div(
                    className="card",
                    children=[
                        html.Label("Partenza", className="field-label"),
                        dcc.Input(
                            id="origin",
                            type="text",
                            placeholder="Es. Stazione Termini, Roma",
                            className="text-input",
                        ),
                        html.Div(
                            className="waypoints-header",
                            children=[
                                html.Span("Tappe intermedie"),
                                html.Button(
                                    "Aggiungi tappa",
                                    id="add-stop",
                                    n_clicks=0,
                                    className="secondary-button",
                                ),
                            ],
                        ),
                        html.Div(id="stops-container", className="stops-container"),
                        html.Label("Destinazione finale", className="field-label"),
                        dcc.Input(
                            id="destination",
                            type="text",
                            placeholder="Es. Aeroporto di Fiumicino",
                            className="text-input",
                        ),
                    ],
                ),
                html.Div(
                    className="card",
                    children=[
                        html.H2("Parametri tariffa"),
                        html.Div(
                            className="fare-grid",
                            children=[
                                html.Div(
                                    className="field",
                                    children=[
                                        html.Label("Tariffa base", className="field-label"),
                                        dcc.Input(
                                            id="base-fare",
                                            type="number",
                                            value=4.50,
                                            min=0,
                                            step=0.01,
                                            className="text-input",
                                        ),
                                    ],
                                ),
                                html.Div(
                                    className="field",
                                    children=[
                                        html.Label("Prezzo per km", className="field-label"),
                                        dcc.Input(
                                            id="price-per-km",
                                            type="number",
                                            value=1.35,
                                            min=0,
                                            step=0.01,
                                            className="text-input",
                                        ),
                                    ],
                                ),
                                html.Div(
                                    className="field",
                                    children=[
                                        html.Label("Prezzo per minuto", className="field-label"),
                                        dcc.Input(
                                            id="price-per-minute",
                                            type="number",
                                            value=0.45,
                                            min=0,
                                            step=0.01,
                                            className="text-input",
                                        ),
                                    ],
                                ),
                                html.Div(
                                    className="field",
                                    children=[
                                        html.Label("Supplementi", className="field-label"),
                                        dcc.Input(
                                            id="extra-fees",
                                            type="number",
                                            value=0,
                                            min=0,
                                            step=0.01,
                                            className="text-input",
                                        ),
                                    ],
                                ),
                            ],
                        ),
                        html.Button(
                            "Calcola percorso e tariffa",
                            id="calculate-route",
                            n_clicks=0,
                            className="primary-button",
                        ),
                    ],
                ),
                html.Div(
                    className="card results-card",
                    children=[
                        html.H2("Riepilogo"),
                        html.Div(
                            className="result-row",
                            children=[html.Span("Distanza totale"), html.Strong(id="total-distance")],
                        ),
                        html.Div(
                            className="result-row",
                            children=[html.Span("Durata totale"), html.Strong(id="total-duration")],
                        ),
                        html.Div(
                            className="result-row total-row",
                            children=[html.Span("Tariffa stimata"), html.Strong(id="estimated-fare")],
                        ),
                        html.P(
                            "Inserisci i dati e premi il pulsante per vedere il percorso.",
                            id="status-message",
                            className="status-message",
                        ),
                    ],
                ),
            ],
        ),
        html.Section(
            className="map-panel",
            children=[html.Div(id="map", className="map-canvas")],
        ),
    ],
)


def make_stop_row(stop_id: int) -> html.Div:
    return html.Div(
        className="stop-row",
        children=[
            dcc.Input(
                id={"type": "stop-input", "index": stop_id},
                type="text",
                placeholder=f"Tappa {stop_id + 1}",
                className="text-input",
            ),
            html.Button(
                "Rimuovi",
                id={"type": "remove-stop", "index": stop_id},
                n_clicks=0,
                className="secondary-button remove-stop",
            ),
        ],
    )


def geocode_address(address: str) -> dict[str, Any]:
    response = requests.get(
        NOMINATIM_SEARCH_URL,
        params={"q": address, "format": "jsonv2", "limit": 1},
        headers=HTTP_HEADERS,
        timeout=15,
    )
    response.raise_for_status()
    results = response.json()
    if not results:
        raise ValueError(f"Indirizzo non trovato: {address}")

    match = results[0]
    return {
        "address": address,
        "label": match.get("display_name", address),
        "lat": float(match["lat"]),
        "lon": float(match["lon"]),
    }


def fetch_osrm_route(points: list[dict[str, Any]]) -> dict[str, Any]:
    coordinates = ";".join(f"{point['lon']},{point['lat']}" for point in points)
    response = requests.get(
        f"{OSRM_ROUTE_URL}/{coordinates}",
        params={"overview": "full", "geometries": "geojson", "steps": "false"},
        headers=HTTP_HEADERS,
        timeout=20,
    )
    response.raise_for_status()
    payload = response.json()
    routes = payload.get("routes", [])
    if not routes:
        raise ValueError("OSRM non ha restituito un percorso valido.")
    return routes[0]


def build_summary(
    route: dict[str, Any],
    base_fare: float | None,
    price_per_km: float | None,
    price_per_minute: float | None,
    extra_fees: float | None,
    stop_count: int,
) -> dict[str, str]:
    total_km = route["distance"] / 1000
    total_minutes = route["duration"] / 60
    total_fare = (
        safe_number(base_fare)
        + total_km * safe_number(price_per_km)
        + total_minutes * safe_number(price_per_minute)
        + safe_number(extra_fees)
    )

    return {
        "distance_label": f"{total_km:.2f} km",
        "duration_label": f"{round(total_minutes)} min",
        "fare_label": format_eur(total_fare),
        "status": f"Percorso aggiornato con {stop_count} tappe intermedie.",
    }


def safe_number(value: float | None) -> float:
    if value is None:
        return 0.0
    return float(value)


def format_eur(value: float) -> str:
    formatted = f"{value:,.2f}"
    return f"EUR {formatted}".replace(",", "X").replace(".", ",").replace("X", ".")


@app.callback(
    Output("stops-store", "data"),
    Input("add-stop", "n_clicks"),
    Input({"type": "remove-stop", "index": ALL}, "n_clicks"),
    State("stops-store", "data"),
    prevent_initial_call=True,
)
def manage_stops(_add_clicks: int, _remove_clicks: list[int], stops_data: list[dict]) -> list[dict]:
    trigger = ctx.triggered_id
    if trigger == "add-stop":
        next_id = max((item["id"] for item in stops_data), default=-1) + 1
        return [*stops_data, {"id": next_id}]

    if isinstance(trigger, dict) and trigger.get("type") == "remove-stop":
        stop_id = trigger["index"]
        return [item for item in stops_data if item["id"] != stop_id]

    return stops_data


@app.callback(Output("stops-container", "children"), Input("stops-store", "data"))
def render_stop_inputs(stops_data: list[dict]) -> list[html.Div]:
    return [make_stop_row(item["id"]) for item in stops_data]


@app.callback(
    Output("route-data", "data"),
    Output("route-summary", "data"),
    Input("calculate-route", "n_clicks"),
    State("origin", "value"),
    State("destination", "value"),
    State({"type": "stop-input", "index": ALL}, "value"),
    State("base-fare", "value"),
    State("price-per-km", "value"),
    State("price-per-minute", "value"),
    State("extra-fees", "value"),
    prevent_initial_call=True,
)
def calculate_route(
    _n_clicks: int,
    origin: str | None,
    destination: str | None,
    stops: list[str | None],
    base_fare: float | None,
    price_per_km: float | None,
    price_per_minute: float | None,
    extra_fees: float | None,
) -> tuple[dict[str, Any] | None, dict[str, str]]:
    cleaned_origin = (origin or "").strip()
    cleaned_destination = (destination or "").strip()
    cleaned_stops = [stop.strip() for stop in stops if stop and stop.strip()]

    if not cleaned_origin or not cleaned_destination:
        return None, empty_summary("Inserisci almeno partenza e destinazione finale.")

    try:
        points = [
            geocode_address(cleaned_origin),
            *[geocode_address(stop) for stop in cleaned_stops],
            geocode_address(cleaned_destination),
        ]
        route = fetch_osrm_route(points)
    except requests.RequestException:
        return None, empty_summary(
            "Errore di rete durante il calcolo del percorso. Riprova tra poco."
        )
    except ValueError as exc:
        return None, empty_summary(str(exc))

    route_data = {
        "center": {
            "lat": points[0]["lat"],
            "lon": points[0]["lon"],
        },
        "markers": points,
        "geometry": route["geometry"]["coordinates"],
    }
    summary = build_summary(
        route,
        base_fare,
        price_per_km,
        price_per_minute,
        extra_fees,
        len(cleaned_stops),
    )
    return route_data, summary


@app.callback(
    Output("total-distance", "children"),
    Output("total-duration", "children"),
    Output("estimated-fare", "children"),
    Output("status-message", "children"),
    Input("route-summary", "data"),
)
def update_summary(summary: dict[str, str] | None) -> tuple[str, str, str, str]:
    if not summary:
        return "-", "-", "-", "Inserisci i dati e premi il pulsante per vedere il percorso."

    return (
        summary.get("distance_label", "-"),
        summary.get("duration_label", "-"),
        summary.get("fare_label", "-"),
        summary.get("status", "Percorso aggiornato."),
    )


app.clientside_callback(
    """
    function(routeData) {
        return window.dash_clientside.clientside.renderLeafletRoute(routeData);
    }
    """,
    Output("map-render-signal", "children"),
    Input("route-data", "data"),
)


def empty_summary(message: str) -> dict[str, str]:
    return {
        "distance_label": "-",
        "duration_label": "-",
        "fare_label": "-",
        "status": message,
    }


if __name__ == "__main__":
    app.run(debug=True)
