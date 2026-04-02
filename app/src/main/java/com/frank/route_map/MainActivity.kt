package com.frank.route_map

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.frank.route_map.databinding.ActivityMainBinding
import com.frank.route_map.databinding.ItemStopFieldBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val viareggioBoundaryClient = ViareggioBoundaryClient(httpClient)

    private val stopFields = mutableListOf<ItemStopFieldBinding>()
    private val markers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var latestKmProgressText: String = ""

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
        attachAutocomplete(binding.originInput)
        attachAutocomplete(binding.destinationInput)
        binding.addStopButton.setOnClickListener { addStopField() }
        binding.calculateButton.setOnClickListener { calculateRoute() }
        binding.showKmProgressCheckbox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            refreshKmProgressVisibility(isChecked)
        }
    }

    private fun addStopField(initialValue: String = "") {
        val itemBinding = ItemStopFieldBinding.inflate(LayoutInflater.from(this), binding.stopsContainer, false)
        itemBinding.stopInput.setText(initialValue, false)
        attachAutocomplete(itemBinding.stopInput)
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
        val pricingInputs = PricingInputs(
            fareMode = selectedFareMode(),
            primaryFare = FareParameters(
                baseFare = binding.baseFareInput.numericValue(),
                pricePerKm = binding.pricePerKmInput.numericValue(),
                pricePerMinute = binding.pricePerMinuteInput.numericValue(),
                extraFees = binding.extraFeesInput.numericValue()
            ),
            secondaryFare = FareParameters(
                baseFare = binding.baseFare2Input.numericValue(),
                pricePerKm = binding.pricePerKm2Input.numericValue(),
                pricePerMinute = binding.pricePerMinute2Input.numericValue(),
                extraFees = binding.extraFees2Input.numericValue()
            )
        )

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
                val pricing = withContext(Dispatchers.IO) {
                    calculatePricing(route, pricingInputs)
                }

                renderRoute(points, route.geometry, pricing.routeColorRes)
                renderSummary(route, stops.size, pricing)
            } catch (error: Throwable) {
                showStatus(error.message ?: "Non sono riuscito a calcolare il percorso.")
                Snackbar.make(binding.root, "Errore: ${error.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderRoute(points: List<GeoAddress>, geometry: List<GeoPoint>, routeColorRes: Int) {
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
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, routeColorRes)
            outlinePaint.strokeWidth = 10f
            setPoints(geometry)
        }
        map.overlays.add(routeLine)

        if (geometry.isNotEmpty()) {
            map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geometry), true, 120)
        }
        map.invalidate()
    }

    private fun renderSummary(route: RouteResult, stopCount: Int, pricing: PricingResult) {
        val totalKm = route.distanceMeters / 1000.0
        val totalMinutes = route.durationSeconds / 60.0

        binding.totalDistanceValue.text = String.format(Locale.ITALY, "%.2f km", totalKm)
        binding.totalDurationValue.text = String.format(Locale.ITALY, "%.0f min", totalMinutes)
        binding.estimatedFareValue.text = formatCurrency(pricing.totalFare)
        binding.activeFareProfileValue.text = pricing.appliedFareLabel
        binding.activeFareProfileValue.setTextColor(ContextCompat.getColor(this, pricing.routeColorRes))
        latestKmProgressText = pricing.kmProgressText
        binding.kmProgressText.text = pricing.kmProgressText
        refreshKmProgressVisibility(binding.showKmProgressCheckbox.isChecked)
        showStatus(pricing.statusMessage(stopCount))
    }

    private fun calculatePricing(route: RouteResult, pricingInputs: PricingInputs): PricingResult {
        val totalKm = route.distanceMeters / 1000.0
        val totalMinutes = route.durationSeconds / 60.0
        val primaryFare = pricingInputs.primaryFare
        val secondaryFare = pricingInputs.secondaryFare

        return when (pricingInputs.fareMode) {
            FareMode.SECONDARY -> PricingResult(
                totalFare = secondaryFare.total(totalKm, totalMinutes),
                appliedFareLabel = "Tariffa 2",
                routeColorRes = R.color.route_teal,
                kmProgressText = buildKmProgressText(totalKm, totalMinutes, secondaryFare, "Tariffa 2")
            )
            FareMode.AUTO_VIAREGGIO -> {
                val exitsViareggio = viareggioBoundaryClient.routeExitsViareggio(route.geometry)
                val appliedFare = if (exitsViareggio) secondaryFare else primaryFare
                val appliedLabel = if (exitsViareggio) "Tariffa 2" else "Tariffa 1"
                PricingResult(
                    totalFare = appliedFare.total(totalKm, totalMinutes),
                    appliedFareLabel = appliedLabel,
                    autoViareggio = true,
                    routeExitsViareggio = exitsViareggio,
                    routeColorRes = if (exitsViareggio) R.color.route_teal else R.color.route_orange,
                    kmProgressText = buildKmProgressText(totalKm, totalMinutes, appliedFare, appliedLabel)
                )
            }
            FareMode.PRIMARY -> PricingResult(
                totalFare = primaryFare.total(totalKm, totalMinutes),
                appliedFareLabel = "Tariffa 1",
                routeColorRes = R.color.route_orange,
                kmProgressText = buildKmProgressText(totalKm, totalMinutes, primaryFare, "Tariffa 1")
            )
        }
    }

    private fun buildKmProgressText(
        totalKm: Double,
        totalMinutes: Double,
        fareParameters: FareParameters,
        appliedLabel: String
    ): String {
        if (totalKm <= 0.0) {
            return ""
        }

        val avgMinutesPerKm = if (totalKm > 0.0) totalMinutes / totalKm else 0.0
        val lines = mutableListOf<String>()
        lines += "Progressione $appliedLabel"
        lines += "Base: ${formatCurrency(fareParameters.baseFare)}"

        val fullKm = totalKm.toInt()
        for (km in 1..fullKm) {
            val elapsedMinutes = avgMinutesPerKm * km
            val fareAtKm = fareParameters.total(km.toDouble(), elapsedMinutes)
            lines += String.format(
                Locale.ITALY,
                "%2d km -> %s",
                km,
                formatCurrency(fareAtKm)
            )
        }

        if (totalKm - fullKm > 0.01) {
            val finalFare = fareParameters.total(totalKm, totalMinutes)
            lines += String.format(
                Locale.ITALY,
                "%.2f km -> %s",
                totalKm,
                formatCurrency(finalFare)
            )
        }

        return lines.joinToString(separator = "\n")
    }

    private fun refreshKmProgressVisibility(enabled: Boolean) {
        val visible = enabled && latestKmProgressText.isNotBlank()
        binding.kmProgressContainer.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (visible) {
            binding.kmProgressText.text = latestKmProgressText
        }
    }

    private fun selectedFareMode(): FareMode {
        return when (binding.fareModeGroup.checkedRadioButtonId) {
            R.id.fareModeSecondary -> FareMode.SECONDARY
            R.id.fareModeAutoViareggio -> FareMode.AUTO_VIAREGGIO
            else -> FareMode.PRIMARY
        }
    }

    private fun attachAutocomplete(input: MaterialAutoCompleteTextView) {
        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        input.setAdapter(adapter)
        input.threshold = 3

        var suggestionJob: Job? = null
        input.doAfterTextChanged { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            suggestionJob?.cancel()
            if (query.length < 3) {
                adapter.clear()
                return@doAfterTextChanged
            }

            suggestionJob = lifecycleScope.launch {
                delay(250)
                val suggestions = try {
                    withContext(Dispatchers.IO) { geocoder.suggest(query) }
                } catch (_: Throwable) {
                    emptyList()
                }

                if (!input.isAttachedToWindow) {
                    return@launch
                }

                adapter.clear()
                adapter.addAll(suggestions)
                adapter.notifyDataSetChanged()
                if (suggestions.isNotEmpty() && input.hasFocus()) {
                    input.showDropDown()
                }
            }
        }
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

private enum class FareMode {
    PRIMARY,
    SECONDARY,
    AUTO_VIAREGGIO
}

private data class FareParameters(
    val baseFare: Double,
    val pricePerKm: Double,
    val pricePerMinute: Double,
    val extraFees: Double
) {
    fun total(totalKm: Double, totalMinutes: Double): Double {
        return baseFare + totalKm * pricePerKm + totalMinutes * pricePerMinute + extraFees
    }
}

private data class PricingInputs(
    val fareMode: FareMode,
    val primaryFare: FareParameters,
    val secondaryFare: FareParameters
)

private data class PricingResult(
    val totalFare: Double,
    val appliedFareLabel: String,
    val routeColorRes: Int,
    val kmProgressText: String,
    val autoViareggio: Boolean = false,
    val routeExitsViareggio: Boolean = false
) {
    fun statusMessage(stopCount: Int): String {
        return if (autoViareggio) {
            if (routeExitsViareggio) {
                "Tariffa 2 applicata automaticamente: il percorso esce da Viareggio. Tappe intermedie: $stopCount."
            } else {
                "Tariffa 1 applicata: il percorso resta nei confini di Viareggio. Tappe intermedie: $stopCount."
            }
        } else {
            "Percorso aggiornato con $stopCount tappe intermedie. Applicata $appliedFareLabel."
        }
    }
}

private class NominatimClient(private val httpClient: OkHttpClient) {
    private val stationAliases = listOf(
        "stazione ferroviaria",
        "stazione fs",
        "stazione treni",
        "stazione",
        "railway station",
        "train station"
    )

    fun geocode(query: String): GeoAddress {
        val wantsStation = containsStationHint(query)
        var fallbackItem: JSONObject? = null

        for (attempt in buildQueries(query)) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("nominatim.openstreetmap.org")
                .addPathSegment("search")
                .addQueryParameter("q", attempt)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "5")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("extratags", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteMapAndroid/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Geocoding fallito: ${response.code}")
                }

                val items = JSONArray(response.body?.string().orEmpty())
                if (items.length() == 0) {
                    return@use
                }

                if (fallbackItem == null) {
                    fallbackItem = items.getJSONObject(0)
                }

                val bestItem = selectBestResult(query, attempt, items)
                if (bestItem == null) {
                    return@use
                }

                if (wantsStation && !isStationLike(bestItem)) {
                    if (fallbackItem == null) {
                        fallbackItem = bestItem
                    }
                    return@use
                }

                return GeoAddress(
                    query = query,
                    label = bestItem.optString("display_name", query),
                    lat = bestItem.getString("lat").toDouble(),
                    lon = bestItem.getString("lon").toDouble()
                )
            }
        }

        if (fallbackItem != null) {
            return GeoAddress(
                query = query,
                label = fallbackItem!!.optString("display_name", query),
                lat = fallbackItem!!.getString("lat").toDouble(),
                lon = fallbackItem!!.getString("lon").toDouble()
            )
        }

        throw IllegalArgumentException("Indirizzo non trovato: $query")
    }

    fun suggest(query: String): List<String> {
        val scoredSuggestions = linkedMapOf<String, Double>()

        for (attempt in buildQueries(query)) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("nominatim.openstreetmap.org")
                .addPathSegment("search")
                .addQueryParameter("q", attempt)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "5")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("extratags", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteMapAndroid/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Suggerimenti falliti: ${response.code}")
                }

                val items = JSONArray(response.body?.string().orEmpty())
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val label = item.optString("display_name").trim()
                    if (label.isBlank()) {
                        continue
                    }
                    val score = scoreResult(query, attempt, item)
                    val previous = scoredSuggestions[label]
                    if (previous == null || score > previous) {
                        scoredSuggestions[label] = score
                    }
                }
            }
        }

        return scoredSuggestions.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
    }

    private fun buildQueries(original: String): List<String> {
        val queries = linkedSetOf<String>()
        val normalized = original.trim().replace(Regex("\\s+"), " ")
        queries += normalized
        queries += normalized.replace(",", " ").replace(Regex("\\s+"), " ")

        val parts = normalized.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val locationParts = parts.filterNot(::containsStationHint)
            val stationParts = parts.filter(::containsStationHint)
            if (locationParts.isNotEmpty() && stationParts.isNotEmpty()) {
                val location = locationParts.joinToString(" ")
                queries += "railway station $location"
                queries += "$location railway station"
                queries += "train station $location"
                queries += "$location train station"
                queries += "stazione di $location"
            }
        }

        if (containsStationHint(normalized)) {
            var location = normalized.lowercase(Locale.ROOT)
            stationAliases.forEach { alias ->
                location = location.replace(alias, " ")
            }
            location = location.replace(",", " ").replace(Regex("\\s+"), " ").trim()
            if (location.isNotEmpty()) {
                queries += "railway station $location"
                queries += "$location railway station"
                queries += "train station $location"
                queries += "$location train station"
                queries += "stazione di $location"
            }
        }

        return queries.filter { it.isNotBlank() }
    }

    private fun selectBestResult(
        originalQuery: String,
        attemptedQuery: String,
        items: JSONArray
    ): JSONObject? {
        var bestItem: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val score = scoreResult(originalQuery, attemptedQuery, item)
            if (score > bestScore) {
                bestScore = score
                bestItem = item
            }
        }

        return bestItem
    }

    private fun scoreResult(originalQuery: String, attemptedQuery: String, item: JSONObject): Double {
        val label = item.optString("display_name", "").lowercase(Locale.ROOT)
        val type = item.optString("type", "")
        val importance = item.optDouble("importance", 0.0)
        val extratags = item.optJSONObject("extratags")
        val address = item.optJSONObject("address")

        var score = importance * 100.0

        if (type == "station") score += 120.0
        if (extratags?.optString("public_transport") in listOf("station", "stop_position", "platform")) score += 90.0
        if (extratags?.optString("train") == "yes") score += 70.0
        if (!address?.optString("railway").isNullOrBlank()) score += 50.0
        if ("station" in label || "stazione" in label) score += 20.0

        tokenizeForMatch(originalQuery).forEach { token ->
            if (token in label) score += 10.0
        }

        val wantsStation = containsStationHint(originalQuery) || containsStationHint(attemptedQuery)
        val isStationLike =
            type == "station" ||
            extratags?.optString("public_transport") in listOf("station", "stop_position", "platform") ||
            extratags?.optString("train") == "yes"

        if (wantsStation && !isStationLike) score -= 80.0
        if (type in listOf("parking", "car_wash", "fuel", "tertiary")) score -= 40.0

        return score
    }

    private fun isStationLike(item: JSONObject): Boolean {
        val extratags = item.optJSONObject("extratags")
        return item.optString("type") in listOf("station", "train_station") ||
            extratags?.optString("public_transport") in listOf("station", "stop_position", "platform") ||
            extratags?.optString("train") == "yes"
    }

    private fun containsStationHint(value: String): Boolean {
        val lowered = value.lowercase(Locale.ROOT)
        return stationAliases.any { it in lowered }
    }

    private fun tokenizeForMatch(value: String): List<String> {
        return value
            .lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace("-", " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
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

private class ViareggioBoundaryClient(private val httpClient: OkHttpClient) {
    private var cachedPolygons: List<List<Pair<Double, Double>>>? = null

    fun routeExitsViareggio(routeGeometry: List<GeoPoint>): Boolean {
        if (routeGeometry.isEmpty()) {
            return false
        }
        val polygons = boundaryPolygons()
        return routeGeometry.any { point ->
            !isInsideAnyPolygon(point.latitude, point.longitude, polygons)
        }
    }

    private fun boundaryPolygons(): List<List<Pair<Double, Double>>> {
        cachedPolygons?.let { return it }

        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("nominatim.openstreetmap.org")
            .addPathSegment("search")
            .addQueryParameter("q", "Viareggio, Lucca, Toscana, Italia")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .addQueryParameter("polygon_geojson", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RouteMapAndroid/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Recupero confini Viareggio fallito: ${response.code}")
            }

            val payload = JSONArray(response.body?.string().orEmpty())
            if (payload.length() == 0) {
                throw IllegalStateException("Non sono riuscito a recuperare i confini di Viareggio.")
            }

            val geojson = payload.getJSONObject(0).optJSONObject("geojson")
                ?: throw IllegalStateException("I confini di Viareggio non sono disponibili.")
            val polygons = extractPolygons(geojson)
            if (polygons.isEmpty()) {
                throw IllegalStateException("I confini di Viareggio non sono disponibili.")
            }
            cachedPolygons = polygons
            return polygons
        }
    }

    private fun extractPolygons(geojson: JSONObject): List<List<Pair<Double, Double>>> {
        val type = geojson.optString("type")
        val coordinates = geojson.optJSONArray("coordinates") ?: JSONArray()
        val polygons = mutableListOf<List<Pair<Double, Double>>>()

        if (type == "Polygon") {
            if (coordinates.length() > 0) {
                polygons += ringToPairs(coordinates.getJSONArray(0))
            }
        } else if (type == "MultiPolygon") {
            for (index in 0 until coordinates.length()) {
                val polygon = coordinates.getJSONArray(index)
                if (polygon.length() > 0) {
                    polygons += ringToPairs(polygon.getJSONArray(0))
                }
            }
        }

        return polygons
    }

    private fun ringToPairs(ring: JSONArray): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        for (index in 0 until ring.length()) {
            val coordinate = ring.getJSONArray(index)
            points += coordinate.getDouble(0) to coordinate.getDouble(1)
        }
        return points
    }

    private fun isInsideAnyPolygon(
        lat: Double,
        lon: Double,
        polygons: List<List<Pair<Double, Double>>>
    ): Boolean {
        return polygons.any { polygon -> isInsidePolygon(lat, lon, polygon) }
    }

    private fun isInsidePolygon(
        lat: Double,
        lon: Double,
        polygon: List<Pair<Double, Double>>
    ): Boolean {
        if (polygon.size < 3) {
            return false
        }

        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val currentLon = current.first
            val currentLat = current.second
            val previousLon = previous.first
            val previousLat = previous.second

            val intersects = ((currentLat > lat) != (previousLat > lat)) &&
                (lon < (previousLon - currentLon) * (lat - currentLat) / ((previousLat - currentLat).takeIf { it != 0.0 } ?: 1e-12) + currentLon)
            if (intersects) {
                inside = !inside
            }
            previous = current
        }

        return inside
    }
}
