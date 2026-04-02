window.leafletTaxiMap = {
  map: null,
  markersLayer: null,
  routeLayer: null,

  ensureMap: function () {
    if (!window.L) {
      return null;
    }

    if (this.map) {
      return this.map;
    }

    const map = L.map("map", {
      zoomControl: true
    }).setView([41.9028, 12.4964], 11);

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);

    this.markersLayer = L.layerGroup().addTo(map);
    this.routeLayer = L.layerGroup().addTo(map);
    this.map = map;
    return map;
  },

  renderRoute: function (routeData) {
    const map = this.ensureMap();
    if (!map) {
      return "leaflet-not-ready";
    }

    this.markersLayer.clearLayers();
    this.routeLayer.clearLayers();

    if (!routeData || !routeData.geometry || routeData.geometry.length === 0) {
      map.setView([41.9028, 12.4964], 11);
      return "map-ready";
    }

    const latLngs = routeData.geometry.map(function (point) {
      return [point[1], point[0]];
    });

    const polyline = L.polyline(latLngs, {
      color: "#d86117",
      weight: 6,
      opacity: 0.9
    }).addTo(this.routeLayer);

    (routeData.markers || []).forEach((marker, index) => {
      const label = index === 0
        ? "Partenza"
        : index === routeData.markers.length - 1
          ? "Destinazione"
          : "Tappa " + index;

      L.marker([marker.lat, marker.lon])
        .bindPopup("<strong>" + label + "</strong><br>" + marker.label)
        .addTo(this.markersLayer);
    });

    map.fitBounds(polyline.getBounds(), { padding: [40, 40] });
    return "route-rendered";
  }
};

window.dash_clientside = Object.assign({}, window.dash_clientside, {
  clientside: {
    renderLeafletRoute: function (routeData) {
      return window.leafletTaxiMap.renderRoute(routeData);
    }
  }
});

window.addEventListener("load", function () {
  window.leafletTaxiMap.ensureMap();
});
