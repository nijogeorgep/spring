package org.abhijitsarkar.spring.pinterest.web

import org.abhijitsarkar.spring.pinterest.client.Pinterest.PinterestJsonFormat.AccessTokenRequest
import org.abhijitsarkar.spring.pinterest.client.Pinterest.PinterestJsonFormat.AccessTokenResponse
import org.abhijitsarkar.spring.pinterest.service.PinterestService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.Cache
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils.isEmpty
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.net.URI

/**
 * @author Abhijit Sarkar
 */
@Component
class OAuthHandler(val pinterestService: PinterestService, val cache: Cache) {
    val logger: Logger = LoggerFactory.getLogger(OAuthHandler::class.java)

    @Value("\${pinterest.clientId}")
    lateinit var clientId: String

    @Value("\${pinterest.clientSecret}")
    lateinit var clientSecret: String

    fun redirect(request: ServerRequest): Mono<ServerResponse> {
        val sessionId = request.headers().header(SESSION_ID).firstOrNull()

        return if (isEmpty(sessionId)) {
            ServerResponse.unprocessableEntity().body(BodyInserters.fromObject("Session id not found!"))
        } else {
            logger.debug("Found session id in redirect: {}.", sessionId)

            UriComponentsBuilder.fromUriString("https://api.pinterest.com/oauth/")
                    .queryParam("response_type", "code")
                    .queryParam("scope", "read_public,write_public")
                    .queryParam("state", sessionId)
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", request.uri().resolve(URI(OAUTH_TOKEN))
                            // https://jira.spring.io/browse/SPR-15931
                            .toString().replace("http", "https"))
                    .build()
                    .toUri()
                    .also { logger.debug("Redirecting to: $it.") }
                    .let { ServerResponse.permanentRedirect(it).build() }
        }
    }


    fun accessToken(request: ServerRequest): Mono<ServerResponse> {
        val sessionId = request.headers().header(SESSION_ID).firstOrNull()

        return if (isEmpty(sessionId)) {
            ServerResponse.unprocessableEntity().body(BodyInserters.fromObject("Session id not found!"))
        } else {
            request.queryParam("code")
                    .toMono()
                    .flatMap { if (it.isPresent) Mono.just(it.get()) else Mono.empty() }
                    .doOnNext { logger.debug("Received access code: $it.") }
                    .flatMap { pinterestService.getAccessToken(AccessTokenRequest(clientId, clientSecret, it)) }
                    .map(AccessTokenResponse::accessToken)
                    .doOnNext { accessToken ->
                        logger.debug("Received access token: {}.", accessToken)
                        logger.debug("Found session id in accessToken: {}.", sessionId)
                        cache.put(sessionId, accessToken)
                    }
                    .doOnError { logger.error("Failed to get access token.", it) }
                    .flatMap { ServerResponse.seeOther(URI(PIN)).build() }
        }
    }
}