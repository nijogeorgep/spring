package org.abhijitsarkar.spring.pinterest.web

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI

/**
 * @author Abhijit Sarkar
 */
@Component
class OAuthFilter(val cache: Cache) : WebFilter {
    val logger: Logger = LoggerFactory.getLogger(OAuthFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return exchange.session.flatMap { session ->
            val path = exchange.request.path.pathWithinApplication().value()

            logger.debug("Request URI: {}.", exchange.request.uri)

            var sessionId = exchange.request.queryParams["state"]?.firstOrNull()

            if (StringUtils.isEmpty(sessionId)) {
                if (!session.isStarted) {
                    logger.debug("Started session: {}.", session.id)
                    session.start()
                }

                sessionId = session.id
            } else {
                logger.debug("OAuth redirect from Pinterest.")
            }

            logger.debug("Session id: {}.", sessionId)

            val modifiedExchange = exchange.mutate().request(
                    exchange.request.mutate().header(SESSION_ID, sessionId).build())
                    .build()
                    .also { it.response.beforeCommit { session.save() } }

            if (isNotPreAuthorized(path, sessionId)) {
                modifiedExchange.response.apply {
                    modifiedExchange.request.uri.resolve(URI(OAUTH))
                            .toString()
                            .replace("http", "https")
                            .also {
                                logger.info("Unauthorized access to: {}, redirecting to: {}.",
                                        modifiedExchange.request.uri, it)
                                headers[HttpHeaders.LOCATION] = it
                            }
                    statusCode = HttpStatus.TEMPORARY_REDIRECT
                }
                        .setComplete()
            }
            chain.filter(modifiedExchange)
        }
    }

    private fun isNotPreAuthorized(path: String, sessionId: String?) =
            path.startsWith(PINTEREST)
                    && cache.get(sessionId, String::class.java) == null
}