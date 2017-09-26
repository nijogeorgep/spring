package org.abhijitsarkar.spring.beer;

import com.couchbase.client.java.document.RawJsonDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.abhijitsarkar.spring.beer.domain.Beer;
import org.abhijitsarkar.spring.beer.domain.Brewery;
import org.abhijitsarkar.spring.beer.factory.CouchbaseAsyncBucketFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import rx.Observable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.springframework.util.CollectionUtils.isEmpty;

/**
 * @author Abhijit Sarkar
 */
@RequiredArgsConstructor
@Slf4j
public class DbInitializer {
    private final CouchbaseAsyncBucketFactory couchbaseAsyncBucketFactory;

    private final AtomicBoolean ranOnce = new AtomicBoolean();

    @EventListener
    void doWhenApplicationIsReady(ApplicationReadyEvent event) {
        Observable.interval(30, 30, TimeUnit.SECONDS)
                .zipWith(Observable.just(1).flatMap(i -> {
                    log.info("Initializing database.");

                    ObjectMapper objectMapper = new ObjectMapper();

                    try (InputStream breweries = new ClassPathResource("/breweries.json").getInputStream()) {
                        List<Brewery> list1 = objectMapper.readValue(breweries, new TypeReference<List<Brewery>>() {
                        });

                        return save(toJsonDocuments(list1, Brewery::getName, objectMapper))
                                .flatMap(x -> {
                                    try (InputStream beers = new ClassPathResource("/beers.json").getInputStream()) {
                                        List<Beer> list2 = objectMapper.readValue(beers, new TypeReference<List<Beer>>() {
                                        });

                                        return save(toJsonDocuments(list2, Beer::getName, objectMapper));
                                    } catch (IOException e) {
                                        return Observable.error(e);
                                    }
                                })
                                .toList();
                    } catch (IOException e) {
                        return Observable.error(e);
                    }
                }), (i, l) -> l)
                .filter(l -> !isEmpty(l))
                .toCompletable()
                .doOnCompleted(() -> {
                    boolean initialized = ranOnce.compareAndSet(false, true);
                    log.info("Database: {} initialized.",
                            initialized ? "successfully" : "cannot be");
                })
                .doOnError(t -> log.error("Failed to initialize database.", t))
                .await(5, TimeUnit.SECONDS);
    }

    private Observable<RawJsonDocument> save(List<RawJsonDocument> list) {
        return couchbaseAsyncBucketFactory.getAsyncBucketInstance()
                .flatMapObservable(bucket -> list
                        .stream()
                        .map(bucket::upsert)
                        .collect(collectingAndThen(toList(), Observable::from)))
                .flatMap(obs -> obs)
                .onErrorResumeNext(t -> Observable.empty())
                .doOnNext(doc -> log.debug("Successfully saved doc with id: {}.", doc.id()));
    }

    private <T> List<RawJsonDocument> toJsonDocuments(
            Collection<T> list,
            Function<T, String> idMapper,
            ObjectMapper objectMapper
    ) {
        return list.stream()
                .map(element -> {
                    try {
                        return RawJsonDocument.create(
                                idMapper.apply(element),
                                objectMapper.writeValueAsString(element)
                        );
                    } catch (JsonProcessingException e) {
                        log.error("Failed to convert element: {} to JsonStringDocument.", element, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(toList());
    }
}
