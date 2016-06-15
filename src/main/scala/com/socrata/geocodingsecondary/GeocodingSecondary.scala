package com.socrata.geocodingsecondary

import com.netflix.astyanax.AstyanaxContext
import com.rojoma.simplearm.v2.Resource
import com.socrata.datacoordinator.secondary.feedback.ComputationHandler
import com.socrata.datacoordinator.secondary.feedback.instance.FeedbackSecondaryInstance
import com.socrata.geocoders._
import com.socrata.geocoders.caching.{NoopCacheClient, CassandraCacheClient}
import com.socrata.geocodingsecondary.config.GeocodingSecondaryConfig
import com.socrata.soql.types.{SoQLValue, SoQLType}
import com.socrata.thirdparty.astyanax.AstyanaxFromConfig
import com.typesafe.config.{ConfigFactory, Config}

class GeocodingSecondary(config: GeocodingSecondaryConfig) extends FeedbackSecondaryInstance(config) {
  // SecondaryWatcher will give me a config, but just in case fallback to config from my jar file
  def this(rawConfig: Config) = this(new GeocodingSecondaryConfig(rawConfig.withFallback(
    ConfigFactory.load(classOf[GeocodingSecondary].getClassLoader).getConfig("com.socrata.geocoding-secondary"))))

  implicit def astyanaxResource[T] = new Resource[AstyanaxContext[T]] {
    def close(k: AstyanaxContext[T]) = k.shutdown()
  }
  val keyspace = res(AstyanaxFromConfig.unmanaged(config.cassandra))
  guarded(keyspace.start())

  val geocoderProvider: OptionalGeocoder = locally {
    val geoConfig = config.geocoder

    def baseProvider: BaseGeocoder = geoConfig.mapQuest match {
      case Some(e) => new MapQuestGeocoder(httpClient, e.appToken, { (_, _) => }) // retry count defaults to 5
      case None => log.warn("No MapQuest config provided; using {}.", BaseNoopGeocoder.getClass); BaseNoopGeocoder
    }

    def provider: Geocoder = {
      val cache = geoConfig.cache match {
        case Some(cacheConfig) =>
          new CassandraCacheClient(keyspace.getClient, cacheConfig.columnFamily, cacheConfig.ttl)
        case None =>
          log.warn("No cache config provided; using {}.", NoopCacheClient.getClass)
          NoopCacheClient
      }
      new CachingGeocoderAdapter(cache, baseProvider, { _ => }, geoConfig.filterMultipier)
    }

    new OptionRemoverGeocoder(provider, multiplier = 1 /* we don't want to batch filtering out Nones */)
  }

  override val retryLimit = config.computationRetries
  override val user = "geocoding-secondary"

  override val computationHandlers: Seq[ComputationHandler[SoQLType, SoQLValue]] =
    List(new GeocodingHandler(geocoderProvider))

}
