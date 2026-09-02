package com.jarvis.ai.provider.adapters

import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import com.jarvis.ai.provider.Jsons
import com.jarvis.ai.provider.SecretsSource
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.IOException

private fun dataGet(transport: HttpTransport, url: String, headers: Map<String, String>): String {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (k, v) -> builder.header(k, v) }
    transport.execute(builder.build()).use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
        }
        return response.body?.string() ?: throw IOException("Empty body.")
    }
}

// ===========================================================================
// WEATHER — CurrentWeather(tempC, condition)
// ===========================================================================

data class CurrentWeather(val tempC: Double?, val condition: String)

interface WeatherProvider {
    val providerId: String
    suspend fun current(city: String): CurrentWeather
}

@Serializable
internal data class OwxMain(val temp: Double? = null)

@Serializable
internal data class OwxWeatherItem(val description: String? = null)

@Serializable
internal data class OwxResponse(
    val main: OwxMain = OwxMain(),
    val weather: List<OwxWeatherItem> = emptyList()
)

class OpenWeatherProvider(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : WeatherProvider {
    override val providerId = "openweather"

    override suspend fun current(city: String): CurrentWeather {
        val key = secrets.get("OPENWEATHER_API_KEY#1") ?: throw IOException("openweather: key missing")
        val url = "https://api.openweathermap.org/data/2.5/weather" +
            "?q=${android.net.Uri.encode(city)}&units=metric&appid=$key"
        val decoded = Jsons.lenient.decodeFromString(
            OwxResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        return CurrentWeather(decoded.main.temp, decoded.weather.firstOrNull()?.description ?: "n/a")
    }
}

@Serializable
internal data class VcCurrentConditions(val temp: Double? = null, val conditions: String? = null)

@Serializable
internal data class VcResponse(val currentConditions: VcCurrentConditions = VcCurrentConditions())

class VisualCrossingProvider(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : WeatherProvider {
    override val providerId = "visualcrossing"

    override suspend fun current(city: String): CurrentWeather {
        val key = secrets.get("VISUALCROSSING_API_KEY#1")
            ?: throw IOException("visualcrossing: key missing")
        val url = "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline/" +
            "${android.net.Uri.encode(city)}?unitGroup=metric&key=$key&contentType=json"
        val decoded = Jsons.lenient.decodeFromString(
            VcResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        return CurrentWeather(decoded.currentConditions.temp, decoded.currentConditions.conditions ?: "n/a")
    }
}

// ===========================================================================
// NEWS — NewsItem(title, description, url, publishedAt)
// ===========================================================================

data class NewsItem(val title: String, val description: String, val url: String, val publishedAt: String)

interface NewsProvider {
    val providerId: String
    suspend fun headlines(topic: String?): List<NewsItem>
}

@Serializable
internal data class NwsArticle(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val publishedAt: String? = null
)

@Serializable
internal data class NwsResponse(val articles: List<NwsArticle> = emptyList())

class NewsApiProvider(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : NewsProvider {
    override val providerId = "newsapi"

    override suspend fun headlines(topic: String?): List<NewsItem> {
        val key = secrets.get("NEWSAPI_KEY#1") ?: throw IOException("newsapi: key missing")
        val q = topic?.takeIf { it.isNotBlank() }?.let { "&q=${android.net.Uri.encode(it)}" }.orEmpty()
        val url = "https://newsapi.org/v2/top-headlines?country=us&apiKey=$key$q"
        val decoded = Jsons.lenient.decodeFromString(
            NwsResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        return decoded.articles.mapNotNull { a ->
            val t = a.title ?: return@mapNotNull null
            NewsItem(t, a.description.orEmpty(), a.url.orEmpty(), a.publishedAt.orEmpty())
        }
    }
}

@Serializable
internal data class GNewsArticle(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val publishedAt: String? = null
)

@Serializable
internal data class GNewsResponse(val articles: List<GNewsArticle> = emptyList())

class GNewsProvider(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : NewsProvider {
    override val providerId = "gnews"

    override suspend fun headlines(topic: String?): List<NewsItem> {
        val token = secrets.get("GNEWS_API_KEY#1") ?: throw IOException("gnews: key missing")
        val url = if (topic.isNullOrBlank()) {
            "https://gnews.io/api/v4/top-headlines?lang=en&token=$token"
        } else {
            "https://gnews.io/api/v4/search?q=${android.net.Uri.encode(topic)}&lang=en&token=$token"
        }
        val decoded = Jsons.lenient.decodeFromString(
            GNewsResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        return decoded.articles.mapNotNull { a ->
            val t = a.title ?: return@mapNotNull null
            NewsItem(t, a.description.orEmpty(), a.url.orEmpty(), a.publishedAt.orEmpty())
        }
    }
}

// ===========================================================================
// MAPS — GeoPoint(lat, lon, label)
// ===========================================================================

data class GeoPoint(val lat: Double, val lon: Double, val label: String)

interface MapsProvider {
    val providerId: String
    suspend fun geocode(address: String): GeoPoint
}

@Serializable
internal data class TtPosition(val lat: Double? = null, val lon: Double? = null)

@Serializable
internal data class TtResultItem(val position: TtPosition = TtPosition())

@Serializable
internal data class TtResponse(val results: List<TtResultItem> = emptyList())

class TomTomMaps(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : MapsProvider {
    override val providerId = "tomtom"

    override suspend fun geocode(address: String): GeoPoint {
        val key = secrets.get("TOMTOM_API_KEY#1") ?: throw IOException("tomtom: key missing")
        val url = "https://api.tomtom.com/search/2/geocode/" +
            "${android.net.Uri.encode(address)}.json?key=$key"
        val decoded = Jsons.lenient.decodeFromString(
            TtResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        val pos = decoded.results.firstOrNull()?.position
            ?: throw IOException("tomtom: no result for $address")
        return GeoPoint(pos.lat ?: 0.0, pos.lon ?: 0.0, address)
    }
}

@Serializable
internal data class GeoGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class GeoFeature(val geometry: GeoGeometry = GeoGeometry())

@Serializable
internal data class GeoResponse(val features: List<GeoFeature> = emptyList())

class GeoapifyMaps(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : MapsProvider {
    override val providerId = "geoapify"

    override suspend fun geocode(address: String): GeoPoint {
        val key = secrets.get("GEOAPIFY_API_KEY#1") ?: throw IOException("geoapify: key missing")
        val url = "https://api.geoapify.com/v1/geocode/search" +
            "?text=${android.net.Uri.encode(address)}&apiKey=$key"
        val decoded = Jsons.lenient.decodeFromString(
            GeoResponse.serializer(), dataGet(transport, url, emptyMap())
        )
        val coords = decoded.features.firstOrNull()?.geometry?.coordinates.orEmpty()
        if (coords.size < 2) throw IOException("geoapify: no result for $address")
        return GeoPoint(coords[1], coords[0], address)
    }
}

// ===========================================================================
// FINANCE — StockQuote(symbol, price, changePercent)
// ===========================================================================

data class StockQuote(val symbol: String, val price: Double?, val changePercent: String?)

interface FinanceProvider {
    val providerId: String
    suspend fun quote(symbol: String): StockQuote
}

@Serializable
internal data class AvGlobalQuote(
    @kotlinx.serialization.SerialName("01. symbol") val symbol: String? = null,
    @kotlinx.serialization.SerialName("05. price") val price: String? = null,
    @kotlinx.serialization.SerialName("10. change percent") val changePercent: String? = null
)

@Serializable
internal data class AvWrapper(@kotlinx.serialization.SerialName("Global Quote") val quote: AvGlobalQuote = AvGlobalQuote())

class AlphaVantageFinance(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : FinanceProvider {
    override val providerId = "alphavantage"

    override suspend fun quote(symbol: String): StockQuote {
        val key = secrets.get("ALPHAVANTAGE_API_KEY#1") ?: throw IOException("alphavantage: key missing")
        val url = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" +
            "${android.net.Uri.encode(symbol)}&apikey=$key"
        val decoded = Jsons.lenient.decodeFromString(
            AvWrapper.serializer(), dataGet(transport, url, emptyMap())
        )
        val q = decoded.quote
        return StockQuote(q.symbol ?: symbol, q.price?.toDoubleOrNull(), q.changePercent)
    }
}

@Serializable
internal data class FhQuote(
    @kotlinx.serialization.SerialName("c") val c: Double? = null,
    @kotlinx.serialization.SerialName("dp") val dp: Double? = null
)

class FinnhubFinance(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : FinanceProvider {
    override val providerId = "finnhub"

    override suspend fun quote(symbol: String): StockQuote {
        val token = secrets.get("FINNHUB_API_KEY#1") ?: throw IOException("finnhub: key missing")
        val url = "https://finnhub.io/api/v1/quote?symbol=${android.net.Uri.encode(symbol)}&token=$token"
        val decoded = Jsons.lenient.decodeFromString(
            FhQuote.serializer(), dataGet(transport, url, emptyMap())
        )
        return StockQuote(symbol, decoded.c, decoded.dp?.let { "%.2f".format(it) + "%" })
    }
}

// ===========================================================================
// SPACE — SpaceFact(title, explanation, imageUrl, date)
// ===========================================================================

data class SpaceFact(val title: String, val explanation: String, val imageUrl: String, val date: String)

interface SpaceProvider {
    val providerId: String
    suspend fun astronomyPictureOfTheDay(): SpaceFact
}

@Serializable
internal data class NasaApod(
    val title: String? = null,
    val explanation: String? = null,
    val url: String? = null,
    @kotlinx.serialization.SerialName("hdurl") val hdurl: String? = null,
    @kotlinx.serialization.SerialName("media_type") val mediaType: String? = null,
    val date: String? = null
)

class NasaSpace(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : SpaceProvider {
    override val providerId = "nasa"

    override suspend fun astronomyPictureOfTheDay(): SpaceFact {
        val key = secrets.get("NASA_API_KEY#1") ?: throw IOException("nasa: key missing")
        val url = "https://api.nasa.gov/planetary/apod?api_key=$key"
        val decoded = Jsons.lenient.decodeFromString(
            NasaApod.serializer(), dataGet(transport, url, emptyMap())
        )
        return SpaceFact(
            decoded.title ?: "Astronomy Picture of the Day",
            decoded.explanation.orEmpty(),
            if (decoded.mediaType == "video") "" else decoded.hdurl ?: decoded.url.orEmpty(),
            decoded.date.orEmpty()
        )
    }
}
