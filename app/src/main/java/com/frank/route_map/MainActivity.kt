package com.frank.route_map

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.frank.route_map.databinding.ActivityMainBinding
import com.frank.route_map.databinding.ItemStopFieldBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var mapController: IMapController

    private val httpClient = OkHttpClient.Builder().build()
    private val geocoder = NominatimClient(httpClient)
    private val router = OsrmClient(httpClient)

    private val stopFields = mutableListOf<ItemStopFieldBinding>()
    private val markers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        map = binding.mapView
        setupMap()
        setupUi()
        addStopField()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        mapController = map.controller
        mapController.setZoom(11.0)
        mapController.setCenter(GeoPoint(41.9028, 12.4964))
    }

    private fun setupUi() {
        binding.addStopButton.setOnClickListener { addStopField() }
        binding.calculateButton.setOnClickListener { calculateRoute() }
    }

    private fun addStopField(initialValue: String = "") {
        val itemBinding = ItemStopFieldBinding.inflate(LayoutInflater.from(this), binding.stopsContainer, false)
        itemBinding.stopInput.setText(initialValue)
        itemBinding.removeStopButton.setOnClickListener {
            binding.stopsContainer.removeView(itemBinding.root)
            stopFields.remove(itemBinding)
        }
        stopFields += itemBinding
        binding.stopsContainer.addView(itemBinding.root)
    }

    private fun calculateRoute() {
        val origin = binding.originInput.text.toString().trim()
        val destination = binding.destinationInput.text.toString().trim()
        val stops = stopFields.map { it.stopInput.text.toString().trim() }.filter { it.isNotEmpty() }

        if (origin.isEmpty() || destination.isEmpty()) {
            showStatus("Inserisci almeno partenza e destinazione finale.")
            return
        }

        setLoading(true)
        showStatus("Calcolo percorso in corso...")

        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    buildList {
                        add(geocoder.geocode(origin))
                        stops.forEach { add(geocoder.geocode(it)) }
                        add(geocoder.geocode(destination))
                    }
                }

                val route = withContext(Dispatchers.IO) {
                    router.route(points)
                }

                renderRoute(points, route.geometry)
                renderSummary(route, stops.size)
            } catch (error: Throwable) {
                showStatus(error.message ?: "Non sono riuscito a calcolare il percorso.")
                Snackbar.make(binding.root, "Errore: ${error.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderRoute(points: List<GeoAddress>, geometry: List<GeoPoint>) {
        markers.forEach(map.overlays::remove)
        markers.clear()
        routeLine?.let(map.overlays::remove)

        val markerIcon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default) as Drawable

        points.forEachIndexed { index, point ->
            val marker = Marker(map).apply {
                position = GeoPoint(point.lat, point.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = markerIcon.constantState?.newDrawable()?.mutate()
                title = when (index) {
                    0 -> "Partenza"
                    points.lastIndex -> "Destinazione"
                    else -> "Tappa $index"
                }
                subDescription = point.label
            }
            markers += marker
            map.overlays.add(marker)
        }

        routeLine = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route_orange)
            outlinePaint.strokeWidth = 10f
            setPoints(geometry)
        }
        map.overlays.add(routeLine)

        if (geometry.isNotEmpty()) {
            map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geometry), true, 120)
        }
        map.invalidate()
    }

    private fun renderSummary(route: RouteResult, stopCount: Int) {
        val totalKm = route.distanceMeters / 1000.0
        val totalMinutes = route.durationSeconds / 60.0
        val fare = fareValue(totalKm, totalMinutes)

        binding.totalDistanceValue.text = String.format(Locale.ITALY, "%.2f km", totalKm)
        binding.totalDurationValue.text = String.format(Locale.ITALY, "%.0f min", totalMinutes)
        binding.estimatedFareValue.text = formatCurrency(fare)
        showStatus("Percorso aggiornato con $stopCount tappe intermedie.")
    }

    private fun fareValue(totalKm: Double, totalMinutes: Double): Double {
        val baseFare = binding.baseFareInput.numericValue()
        val pricePerKm = binding.pricePerKmInput.numericValue()
        val pricePerMinute = binding.pricePerMinuteInput.numericValue()
        val extraFees = binding.extraFeesInput.numericValue()
        return baseFare + totalKm * pricePerKm + totalMinutes * pricePerMinute + extraFees
    }

    private fun EditText.numericValue(): Double = text.toString().trim().toDoubleOrNull() ?: 0.0

    private fun formatCurrency(value: Double): String {
        return String.format(Locale.ITALY, "EUR %.2f", value).replace(".", ",")
    }

    private fun setLoading(loading: Boolean) {
        binding.calculateButton.isEnabled = !loading
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showStatus(message: String) {
        binding.statusMessage.text = message
    }
}

private data class GeoAddress(
    val query: String,
    val label: String,
    val lat: Double,
    val lon: Double
)

private data class RouteResult(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<GeoPoint>
)

private class NominatimClient(private val httpClient: OkHttpClient) {
    fun geocode(query: String): GeoAddress {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("nominatim.openstreetmap.org")
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RouteMapAndroid/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Geocoding fallito: ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val items = JSONArray(body)
            if (items.length() == 0) {
                throw IllegalArgumentException("Indirizzo non trovato: $query")
            }

            val item = items.getJSONObject(0)
            return GeoAddress(
                query = query,
                label = item.optString("display_name", query),
                lat = item.getString("lat").toDouble(),
                lon = item.getString("lon").toDouble()
            )
        }
    }
}

private class OsrmClient(private val httpClient: OkHttpClient) {
    fun route(points: List<GeoAddress>): RouteResult {
        val coordinates = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("router.project-osrm.org")
            .addPathSegments("route/v1/driving/$coordinates")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "false")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RouteMapAndroid/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Routing fallito: ${response.code}")
            }

            val payload = JSONObject(response.body?.string().orEmpty())
            val routes = payload.optJSONArray("routes")
                ?: throw IllegalStateException("OSRM non ha restituito rotte.")
            if (routes.length() == 0) {
                throw IllegalStateException("OSRM non ha restituito rotte.")
            }

            val route = routes.getJSONObject(0)
            return RouteResult(
                distanceMeters = route.getDouble("distance"),
                durationSeconds = route.getDouble("duration"),
                geometry = geoJsonToPoints(route.getJSONObject("geometry").getJSONArray("coordinates"))
            )
        }
    }

    private fun geoJsonToPoints(coordinates: JSONArray): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        for (index in 0 until coordinates.length()) {
            val item = coordinates.getJSONArray(index)
            points += GeoPoint(item.getDouble(1), item.getDouble(0))
        }
        return points
    }
}
