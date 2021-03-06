/*
 * Copyright 2020 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.grpc;

import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.grpc.greeter.GreeterGrpc;
import com.hazelcast.jet.grpc.greeter.GreeterOuterClass.HelloReply;
import com.hazelcast.jet.grpc.greeter.GreeterOuterClass.HelloRequest;
import com.hazelcast.jet.impl.util.ExceptionUtil;
import com.hazelcast.jet.pipeline.BatchStage;
import com.hazelcast.jet.pipeline.BatchStageWithKey;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.ServiceFactory;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.AssertionSinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import io.grpc.BindableService;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static com.hazelcast.jet.grpc.GrpcServices.bidirectionalStreamingService;
import static com.hazelcast.jet.grpc.GrpcServices.unaryService;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GrpcServiceTest extends SimpleTestInClusterSupport {

    private static final int ITEM_COUNT = 10_000;
    Server server;

    @BeforeClass
    public static void setup() {
        initialize(2, new JetConfig());
    }

    @After
    public void teardown() {
        if (server != null) {
            server.shutdown() ;
        }
    }

    @Test
    public void when_bidirectionalStreaming() throws IOException {
        // Given
        server = createServer(new GreeterServiceImpl());
        final int port = server.getPort();

        Pipeline p = Pipeline.create();

        BatchStage<String> stage = p.readFrom(TestSources.items("one", "two", "three", "four"));

        // When
        BatchStage<String> mapped = stage.mapUsingServiceAsync(bidirectionalStreaming(port), (service, item) -> {
            HelloRequest req = HelloRequest.newBuilder().setName(item).build();
            return service.call(req).thenApply(HelloReply::getMessage);
        });

        // Then
        List<String> expected = Arrays.asList("Hello one", "Hello two", "Hello three", "Hello four");
        mapped.writeTo(AssertionSinks.assertAnyOrder(expected));
        instance().newJob(p).join();
    }

    @Test
    public void when_bidirectionalStreaming_distributed() throws IOException {
        // Given
        server = createServer(new GreeterServiceImpl());
        final int port = server.getPort();

        List<String> items = IntStream.range(0, ITEM_COUNT).mapToObj(Integer::toString).collect(toList());

        Pipeline p = Pipeline.create();
        BatchStageWithKey<String, String> stage = p.readFrom(TestSources.items(items))
                                           .groupingKey(i -> i);
        // When
        BatchStage<String> mapped = stage.mapUsingServiceAsync(bidirectionalStreaming(port), (service, key, item) -> {
            HelloRequest req = HelloRequest.newBuilder().setName(item).build();
            return service.call(req).thenApply(HelloReply::getMessage);
        });

        // Then
        mapped.writeTo(AssertionSinks.assertCollected(e -> {
            assertEquals("unexpected number of items received", ITEM_COUNT, e.size());
        }));
        instance().newJob(p).join();
    }


    @Test
    public void when_bidirectionalStreaming_withFaultyService() throws IOException {
        // Given
        server = createServer(new FaultyGreeterServiceImpl());
        final int port = server.getPort();

        Pipeline p = Pipeline.create();

        BatchStage<String> stage = p.readFrom(TestSources.items("one", "two", "three", "four"));

        // When
        BatchStage<String> mapped = stage.mapUsingServiceAsync(bidirectionalStreaming(port), (service, item) -> {
            HelloRequest req = HelloRequest.newBuilder().setName(item).build();
            return service.call(req).thenApply(HelloReply::getMessage);
        });

        // Then
        mapped.writeTo(Sinks.noop());
        try {
            instance().newJob(p).join();
            fail("Job should have failed");
        } catch (Exception e) {
            Throwable ex = ExceptionUtil.peel(e);
            assertThat(ex.getMessage()).contains("com.hazelcast.jet.grpc.impl.StatusRuntimeExceptionJet");
        }
    }

    @Test
    public void when_unary() throws IOException {
        // Given
        server = createServer(new GreeterServiceImpl());
        final int port = server.getPort();

        Pipeline p = Pipeline.create();

        BatchStage<String> source = p.readFrom(TestSources.items("one", "two", "three", "four"));

        // When
        BatchStage<String> mapped = source
                .mapUsingServiceAsync(unary(port), (service, input) -> {
                    HelloRequest request = HelloRequest.newBuilder().setName(input).build();
                    return service.call(request).thenApply(HelloReply::getMessage);
                });

        // Then
        List<String> expected = Arrays.asList("Hello one", "Hello two", "Hello three", "Hello four");
        mapped.writeTo(AssertionSinks.assertAnyOrder(expected));
        instance().newJob(p).join();
    }

    @Test
    public void when_unary_distributed() throws IOException {
        // Given
        server = createServer(new GreeterServiceImpl());
        final int port = server.getPort();

        List<String> items = IntStream.range(0, ITEM_COUNT).mapToObj(Integer::toString).collect(toList());

        Pipeline p = Pipeline.create();

        BatchStageWithKey<String, String> stage = p.readFrom(TestSources.items(items))
                                                   .groupingKey(i -> i);
        // When
        BatchStage<String> mapped = stage.mapUsingServiceAsync(unary(port), (service, key, item) -> {
            HelloRequest req = HelloRequest.newBuilder().setName(item).build();
            return service.call(req).thenApply(HelloReply::getMessage);
        });

        // Then
        mapped.writeTo(AssertionSinks.assertCollected(e -> {
            assertEquals("unexpected number of items received", ITEM_COUNT, e.size());
        }));
        instance().newJob(p).join();
    }

    @Test
    public void when_unary_with_faultyService() throws IOException {
        // Given
        Server server = createServer(new FaultyGreeterServiceImpl());
        final int port = server.getPort();

        Pipeline p = Pipeline.create();

        BatchStage<String> source = p.readFrom(TestSources.items("one", "two", "three", "four"));

        // When
        BatchStage<String> mapped = source
                .mapUsingServiceAsync(unary(port), (service, input) -> {
                    HelloRequest request = HelloRequest.newBuilder().setName(input).build();
                    return service.call(request).thenApply(HelloReply::getMessage);
                });

        // Then
        mapped.writeTo(Sinks.noop());

        try {
            instance().newJob(p).join();
            fail("Job should have failed");
        } catch (Exception e) {
            Throwable ex = ExceptionUtil.peel(e);
            assertThat(ex.getMessage()).contains("com.hazelcast.jet.grpc.impl.StatusRuntimeExceptionJet");
        }
    }

    private ServiceFactory<?, ? extends GrpcService<HelloRequest, HelloReply>> unary(int port) {
        return unaryService(
                () -> ManagedChannelBuilder.forAddress("localhost", port).usePlaintext(),
                channel -> GreeterGrpc.newStub(channel)::sayHelloUnary
        );
    }

    private ServiceFactory<?, ? extends GrpcService<HelloRequest, HelloReply>>
    bidirectionalStreaming(int port) {
        return bidirectionalStreamingService(
                () -> ManagedChannelBuilder.forAddress("localhost", port).usePlaintext(),
                channel -> GreeterGrpc.newStub(channel)::sayHelloBidirectional
        );
    }

    private static Server createServer(BindableService service) throws IOException {
        Server server = ServerBuilder.forPort(0).addService(service).build();
        server.start();
        return server;
    }

    private static class FaultyGreeterServiceImpl extends GreeterGrpc.GreeterImplBase {

        @Override
        public StreamObserver<HelloRequest> sayHelloBidirectional(StreamObserver<HelloReply> responseObserver) {
            return new StreamObserver<HelloRequest>() {
                @Override
                public void onNext(HelloRequest value) {
                    responseObserver.onError(new RuntimeException("something went wrong"));
                }

                @Override
                public void onError(Throwable t) {

                }

                @Override
                public void onCompleted() {

                }
            };
        }

        @Override
        public void sayHelloUnary(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            responseObserver.onError(new RuntimeException("something went wrong"));
        }
    }
    private static class GreeterServiceImpl extends GreeterGrpc.GreeterImplBase {

        @Override
        public void sayHelloUnary(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            HelloReply reply = HelloReply.newBuilder()
                                         .setMessage("Hello " + request.getName())
                                         .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<HelloRequest> sayHelloBidirectional(StreamObserver<HelloReply> responseObserver) {
            return new StreamObserver<HelloRequest>() {

                @Override
                public void onNext(HelloRequest value) {
                    HelloReply reply = HelloReply.newBuilder()
                                                 .setMessage("Hello " + value.getName())
                                                 .build();

                    responseObserver.onNext(reply);
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
