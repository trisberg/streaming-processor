package io.projectriff.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bsideup.liiklus.protocol.AckRequest;
import com.github.bsideup.liiklus.protocol.Assignment;
import com.github.bsideup.liiklus.protocol.PublishRequest;
import com.github.bsideup.liiklus.protocol.ReactorLiiklusServiceGrpc;
import com.github.bsideup.liiklus.protocol.ReceiveReply;
import com.github.bsideup.liiklus.protocol.ReceiveRequest;
import com.github.bsideup.liiklus.protocol.SubscribeReply;
import com.github.bsideup.liiklus.protocol.SubscribeRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Channel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.projectriff.invoker.rpc.InputFrame;
import io.projectriff.invoker.rpc.InputSignal;
import io.projectriff.invoker.rpc.OutputFrame;
import io.projectriff.invoker.rpc.OutputSignal;
import io.projectriff.invoker.rpc.ReactorRiffGrpc;
import io.projectriff.invoker.rpc.StartFrame;
import io.projectriff.processor.serialization.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Main driver class for the streaming processor.
 *
 * <p>Continually pumps data from one or several input streams (see {@code riff-serialization.proto} for this so-called "at rest" format),
 * arranges messages in invocation windows and invokes the riff function over RPC by multiplexing messages from several
 * streams into one RPC channel (see {@code riff-rpc.proto} for the wire format).
 * On the way back, performs the opposite operations: de-muxes results and serializes them back to the corresponding
 * output streams.</p>
 *
 * @author Eric Bottard
 * @author Florent Biville
 */
public class Processor {

    /**
     * ENV VAR key holding the coordinates of the input streams, as a comma separated list of {@code gatewayAddress:port/streamName}.
     *
     * @see FullyQualifiedTopic
     */
    private static final String INPUTS = "INPUTS";

    /**
     * ENV VAR key holding the coordinates of the output streams, as a comma separated list of {@code gatewayAddress:port/streamName}.
     *
     * @see FullyQualifiedTopic
     */
    private static final String OUTPUTS = "OUTPUTS";

    /**
     * ENV VAR key holding the address of the function RPC, as a {@code host:port} string.
     */
    private static final String FUNCTION = "FUNCTION";

    /**
     * ENV VAR key holding the serialized list of content-types expected on the output streams.
     */
    private static final String OUTPUT_CONTENT_TYPES = "OUTPUT_CONTENT_TYPES";

    /**
     * ENV VAR key holding the logical names for input parameter names, as a comma separated list of strings.
     */
    private static final String INPUT_NAMES = "INPUT_NAMES";

    /**
     * ENV VAR key holding the logical names for output result names, as a comma separated list of strings.
     */
    private static final String OUTPUT_NAMES = "OUTPUT_NAMES";

    /**
     * ENV VAR key holding the consumer group string this process should use.
     */
    private static final String GROUP = "GROUP";

    /**
     * The number of retries when testing http connection to the function.
     */
    private static final int NUM_RETRIES = 20;

    /**
     * Keeps track of a single gRPC stub per gateway address.
     */
    private final Map<String, ReactorLiiklusServiceGrpc.ReactorLiiklusServiceStub> liiklusInstancesPerAddress;

    /**
     * The ordered input streams for the function, in parsed form.
     */
    private final List<FullyQualifiedTopic> inputs;

    /**
     * The ordered output streams for the function, in parsed form.
     */
    private final List<FullyQualifiedTopic> outputs;

    /**
     * The ordered logical names for input parameters of the function.
     */
    private final List<String> inputNames;

    /**
     * The ordered logical names for output results of the function.
     */
    private final List<String> outputNames;

    /**
     * The ordered list of expected content-types for function results.
     */
    private final List<String> outputContentTypes;

    /**
     * The consumer group string this process will use to identify itself when reading from the input streams.
     */
    private final String group;

    /**
     * The RPC stub used to communicate with the function process.
     *
     * @see "riff-rpc.proto for the wire format and service definition"
     */
    private final ReactorRiffGrpc.ReactorRiffStub riffStub;

    public static void main(String[] args) throws Exception {

        checkEnvironmentVariables();

        Hooks.onOperatorDebug();

        String functionAddress = System.getenv(FUNCTION);

        List<FullyQualifiedTopic> inputAddressableTopics = FullyQualifiedTopic.parseMultiple(System.getenv(INPUTS));
        List<FullyQualifiedTopic> outputAddressableTopics = FullyQualifiedTopic.parseMultiple(System.getenv(OUTPUTS));
        List<String> inputNames = parseCSV(INPUT_NAMES, inputAddressableTopics.size());
        List<String> outputNames = parseCSV(OUTPUT_NAMES, outputAddressableTopics.size());
        List<String> outputContentTypes = parseContentTypes(System.getenv(OUTPUT_CONTENT_TYPES), outputAddressableTopics.size());

        assertHttpConnectivity(functionAddress);
        Channel fnChannel = NettyChannelBuilder.forTarget(functionAddress)
                .usePlaintext()
                .build();

        Processor processor = new Processor(
                inputAddressableTopics,
                outputAddressableTopics,
                inputNames,
                outputNames,
                outputContentTypes,
                System.getenv(GROUP),
                ReactorRiffGrpc.newReactorStub(fnChannel));

        processor.run();

    }

    private static void checkEnvironmentVariables() {
        List<String> envVars = Arrays.asList(INPUTS, OUTPUTS, OUTPUT_CONTENT_TYPES, FUNCTION, GROUP, INPUT_NAMES, OUTPUT_NAMES);
        if (envVars.stream()
                .anyMatch(v -> (System.getenv(v) == null || System.getenv(v).trim().length() == 0))) {
            System.err.format("Missing one of the following environment variables: %s%n", envVars);
            envVars.forEach(v -> System.err.format("  %s = %s%n", v, System.getenv(v)));
            System.exit(1);
        }
    }

    private static void assertHttpConnectivity(String functionAddress) throws URISyntaxException, IOException, InterruptedException {
        URI uri = new URI("http://" + functionAddress);
        for (int i = 1; i <= NUM_RETRIES; i++) {
            try (Socket s = new Socket(uri.getHost(), uri.getPort())) {
            } catch (ConnectException t) {
                if (i == NUM_RETRIES) {
                    throw t;
                }
                Thread.sleep(i * 100);
            }
        }
    }

    private Processor(List<FullyQualifiedTopic> inputs,
                      List<FullyQualifiedTopic> outputs,
                      List<String> inputNames,
                      List<String> outputNames,
                      List<String> outputContentTypes,
                      String group,
                      ReactorRiffGrpc.ReactorRiffStub riffStub) {

        this.inputs = inputs;
        this.outputs = outputs;
        this.inputNames = inputNames;
        this.outputNames = outputNames;
        Set<FullyQualifiedTopic> allGateways = new HashSet<>(inputs);
        allGateways.addAll(outputs);

        this.liiklusInstancesPerAddress = indexByAddress(allGateways);
        this.outputContentTypes = outputContentTypes;
        this.riffStub = riffStub;
        this.group = group;
    }

    public void run() {
        Flux.fromIterable(inputs)
                .flatMap(fullyQualifiedTopic -> {
                    ReactorLiiklusServiceGrpc.ReactorLiiklusServiceStub inputLiiklus = liiklusInstancesPerAddress.get(fullyQualifiedTopic.getGatewayAddress());
                    return inputLiiklus.subscribe(subscribeRequestForInput(fullyQualifiedTopic.getTopic()))
                            .filter(SubscribeReply::hasAssignment)
                            .map(SubscribeReply::getAssignment)
                            .flatMap(
                                    assignment -> inputLiiklus
                                            .receive(receiveRequestForAssignment(assignment))
                                            .delayUntil(receiveReply -> ack(fullyQualifiedTopic, inputLiiklus, receiveReply, assignment))
                            )
                            .map(receiveReply -> toRiffSignal(receiveReply, fullyQualifiedTopic));
                })
                .transform(this::riffWindowing)
                .map(this::invoke)
                .concatMap(flux ->
                        flux.concatMap(m -> {
                            OutputFrame next = m.getData();
                            FullyQualifiedTopic output = outputs.get(next.getResultIndex());
                            ReactorLiiklusServiceGrpc.ReactorLiiklusServiceStub outputLiiklus = liiklusInstancesPerAddress.get(output.getGatewayAddress());
                            return outputLiiklus.publish(createPublishRequest(next, output.getTopic()));
                        })
                )
                .blockLast();
    }

    private Mono<Empty> ack(FullyQualifiedTopic topic, ReactorLiiklusServiceGrpc.ReactorLiiklusServiceStub stub, ReceiveReply receiveReply, Assignment assignment) {
        System.out.format("ACKing %s for group %s: offset=%d, part=%d%n", topic.getTopic(), this.group, receiveReply.getRecord().getOffset(), assignment.getPartition());
        return stub.ack(AckRequest.newBuilder()
                .setGroup(this.group)
                .setOffset(receiveReply.getRecord().getOffset())
                .setPartition(assignment.getPartition())
                .setTopic(topic.getTopic())
                .build());
    }

    private static Map<String, ReactorLiiklusServiceGrpc.ReactorLiiklusServiceStub> indexByAddress(
            Collection<FullyQualifiedTopic> fullyQualifiedTopics) {
        return fullyQualifiedTopics.stream()
                .map(FullyQualifiedTopic::getGatewayAddress)
                .distinct()
                .collect(Collectors.toMap(
                        address -> address,
                        address -> ReactorLiiklusServiceGrpc.newReactorStub(
                                NettyChannelBuilder.forTarget(address)
                                        .usePlaintext()
                                        .build())
                        )
                )
                ;
    }

    private Flux<OutputSignal> invoke(Flux<InputFrame> in) {
        InputSignal start = InputSignal.newBuilder()
                .setStart(StartFrame.newBuilder()
                        .addAllExpectedContentTypes(this.outputContentTypes)
                        .addAllInputNames(this.inputNames)
                        .addAllOutputNames(this.outputNames)
                        .build())
                .build();

        return riffStub.invoke(Flux.concat(
                Flux.just(start), //
                in.map(frame -> InputSignal.newBuilder().setData(frame).build())));
    }

    /**
     * This converts an RPC representation of an {@link OutputFrame} to an at-rest {@link Message}, and creates a publish request for it.
     */
    private PublishRequest createPublishRequest(OutputFrame next, String topic) {
        Message msg = Message.newBuilder()
                .setPayload(next.getPayload())
                .setContentType(next.getContentType())
                .putAllHeaders(next.getHeadersMap())
                .build();

        return PublishRequest.newBuilder()
                .setValue(msg.toByteString())
                .setTopic(topic)
                .build();
    }

    private static ReceiveRequest receiveRequestForAssignment(Assignment assignment) {
        return ReceiveRequest.newBuilder().setAssignment(assignment).build();
    }

    private <T> Flux<Flux<T>> riffWindowing(Flux<T> linear) {
        return linear.window(Duration.ofSeconds(60));
    }

    /**
     * This converts a liiklus received message (representing an at-rest riff {@link Message}) into an RPC {@link InputFrame}.
     */
    private InputFrame toRiffSignal(ReceiveReply receiveReply, FullyQualifiedTopic fullyQualifiedTopic) {
        int inputIndex = inputs.indexOf(fullyQualifiedTopic);
        if (inputIndex == -1) {
            throw new RuntimeException("Unknown topic: " + fullyQualifiedTopic);
        }
        ByteString bytes = receiveReply.getRecord().getValue();
        try {
            Message message = Message.parseFrom(bytes);
            return InputFrame.newBuilder()
                    .setPayload(message.getPayload())
                    .setContentType(message.getContentType())
                    .setArgIndex(inputIndex)
                    .build();
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

    }

    private SubscribeRequest subscribeRequestForInput(String topic) {
        return SubscribeRequest.newBuilder()
                .setTopic(topic)
                .setGroup(group)
                .setAutoOffsetReset(SubscribeRequest.AutoOffsetReset.LATEST)
                .build();
    }

    private static List<String> parseContentTypes(String json, int outputCount) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<String> contentTypes = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            int actualSize = contentTypes.size();
            if (actualSize != outputCount) {
                throw new RuntimeException(
                        String.format("Expected %d output stream content type(s), got %d.%n\tSee %s", outputCount, actualSize, json)
                );
            }
            return contentTypes;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> parseCSV(String envVarName, int expectedSize) {
        String[] split = System.getenv(envVarName).split(",");
        if (split.length != expectedSize) {
            throw new RuntimeException(String.format("Expected a list of %d values in variable %s, got %d: \"%s\"",
                    expectedSize, envVarName, split.length, System.getenv(envVarName)));
        }
        return Arrays.asList(split);
    }
}