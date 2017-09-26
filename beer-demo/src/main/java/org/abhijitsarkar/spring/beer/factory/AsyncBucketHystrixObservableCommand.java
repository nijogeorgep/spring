package org.abhijitsarkar.spring.beer.factory;

import com.couchbase.client.core.lang.Tuple;
import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.AsyncCluster;
import com.couchbase.client.java.cluster.AsyncClusterManager;
import com.couchbase.client.java.cluster.BucketSettings;
import com.couchbase.client.java.cluster.DefaultBucketSettings;
import com.couchbase.client.java.query.AsyncN1qlQueryRow;
import com.couchbase.client.java.query.Index;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.SimpleN1qlQuery;
import com.couchbase.client.java.query.Statement;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixObservableCommand;
import lombok.extern.slf4j.Slf4j;
import org.abhijitsarkar.spring.beer.CouchbaseProperties;
import org.abhijitsarkar.spring.beer.CouchbaseQueryUtil;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;

import java.util.function.BiFunction;

import static com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Abhijit Sarkar
 */
@Slf4j
public class AsyncBucketHystrixObservableCommand extends HystrixObservableCommand<AsyncBucket> {
    final CouchbaseAsyncClusterFactory couchbaseAsyncClusterFactory;
    final CouchbaseProperties couchbaseProperties;

    Scheduler scheduler = Schedulers.io();

    // indirections for testing
    BiFunction<AsyncBucket, N1qlQuery, Observable<AsyncN1qlQueryRow>> queryExecutor =
            CouchbaseQueryUtil::executeN1qlQuery;

    public AsyncBucketHystrixObservableCommand(
            CouchbaseAsyncClusterFactory couchbaseAsyncClusterFactory,
            CouchbaseProperties couchbaseProperties
    ) {
        super(commandSetter(couchbaseProperties.getBucket()));

        this.couchbaseAsyncClusterFactory = couchbaseAsyncClusterFactory;
        this.couchbaseProperties = couchbaseProperties;
    }

    public static Setter commandSetter(CouchbaseProperties.BucketProperties bucket) {
        return HystrixObservableCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(bucket.getName() + "-grp"))
                .andCommandKey(HystrixCommandKey.Factory.asKey(bucket.getName() + "-cmd"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withCircuitBreakerRequestVolumeThreshold(1)
                                .withCircuitBreakerErrorThresholdPercentage(0)
                                .withExecutionIsolationStrategy(SEMAPHORE)
                                .withExecutionIsolationSemaphoreMaxConcurrentRequests(1)
                                .withFallbackEnabled(false)
                                .withExecutionTimeoutInMilliseconds((int) bucket.getBucketOpenTimeoutMillis())
                );
    }

    @Override
    protected Observable<AsyncBucket> construct() {
        CouchbaseProperties.BucketProperties bucket = couchbaseProperties.getBucket();

        requireNonNull(couchbaseProperties.getAdminUsername(), "Admin username must not be null.");
        requireNonNull(bucket, "BucketProperties must not be null.");
        requireNonNull(bucket.getName(), "Bucket name must not be null.");

        return couchbaseAsyncClusterFactory.getAsyncClusterInstance()
                .flatMapObservable(cluster -> cluster.clusterManager(
                        couchbaseProperties.getAdminUsername(),
                        couchbaseProperties.getAdminPassword())
                        .map(clusterManager -> Tuple.create(cluster, clusterManager))
                )
                .flatMap(tuple -> {
                    AsyncCluster cluster = tuple.value1();
                    AsyncClusterManager clusterManager = tuple.value2();

                    return clusterManager.hasBucket(bucket.getName())
                            .map(hasBucket -> {
                                log.info("Bucket: {} is: {}.", bucket.getName(),
                                        hasBucket ? "already present" : "absent");
                                return !hasBucket && bucket.isCreateIfMissing();
                            })
                            .filter(Boolean::booleanValue)
                            .flatMap(x -> {
                                log.info("Creating bucket: {}.", bucket.getName());
                                return clusterManager.insertBucket(bucketSettings(bucket));
                            })
                            .flatMap(x -> openBucket(cluster))
                            .map(b -> {
                                createPrimaryIndex(b)
                                        .toCompletable()
                                        .doOnCompleted(() -> log.info("Successfully created primary index."))
                                        .doOnError(t -> log.error("Failed to create primary index.", t))
                                        .await(bucket.getBucketOpenTimeoutMillis(), MILLISECONDS);

                                return b;
                            })
                            .switchIfEmpty(openBucket(cluster))
                            .doOnError(t -> log.error("Failed to open bucket: {}.", bucket.getName(), t));
                });
    }

    private final BucketSettings bucketSettings(CouchbaseProperties.BucketProperties bucket) {
        return DefaultBucketSettings.builder()
                .name(bucket.getName())
                .password(bucket.getPassword())
                .enableFlush(bucket.isEnableFlush())
                .quota(bucket.getDefaultQuotaMB())
                .indexReplicas(bucket.isIndexReplicas())
                .build();
    }

    Observable<AsyncN1qlQueryRow> createPrimaryIndex(AsyncBucket bucket) {
        CouchbaseProperties.BucketProperties bucketProperties = couchbaseProperties.getBucket();

        Statement statement = Index.createPrimaryIndex()
                .on(bucketProperties.getName());
        SimpleN1qlQuery query = N1qlQuery.simple(statement);

        return Observable.just(0L)
                // separate index creation so as not to timeout on bucket opening
                .observeOn(scheduler)
                .doOnNext(i -> log.info("Creating primary index."))
                .flatMap(i ->
                        Observable.timer(bucketProperties.getBucketOpenTimeoutMillis(), MILLISECONDS, scheduler)
                                .zipWith(queryExecutor.apply(bucket, query), (j, row) -> row));
    }

    private final Observable<AsyncBucket> openBucket(AsyncCluster cluster) {
        CouchbaseProperties.BucketProperties bucket = couchbaseProperties.getBucket();

        return cluster.openBucket(bucket.getName(), bucket.getPassword())
                .timeout(bucket.getBucketOpenTimeoutMillis(), MILLISECONDS);
    }
}
