

# Stream Processing

A rather broad term, however most commonly associated with:

- Processing a number (possibly infinite) of elements.
- The processing is done by pushing/ pulling them through a "pipeline".
- Such pipeline is composed of operations that modify the elements.
- Operation often expressed as DSL similar to Scala collections (map, flatMap, filter).

## Introducing reactive streams semantics

**Flow Control** is a mechanism to control the rate at which an upstream publisher is emitting data to a downstream subscriber in proportion to the subscriber's receiving capabilities. In general the need of back-pressure can be exemplified in any asynchronous system in which the upstream is producing faster than the downstream consumer is able to process it.

**The Reactive Streams initiative ([website](http://www.reactive-streams.org/)) provides a standard for asynchronous stream processing with non-blocking back-pressure.**

Reactive Streams define:

- A set of interaction Rules (the specification), i.e. how we can send data, when we are allowed to send data, when not, etc.
- A set of types (the SPI).
- A Technology Compliance (test) Kit (TCK).

**The methods in reactive streams are called signals(messages) because they returned type is void (in Scala Unit) - lack of return type enables us, and leads us, to building the system as asynchronous signals. By forcing the signatures to be ... => Unit we allow implementations to dispatch the work asynchronously as quickly as possible, without enforcing much overhead (excerpt for checking validity of the call (i.e. nulls are not allowed)).**

## Akka Streams

- Akka Streams provides a high-level API for streams processing.
- Akka Streams implements the Reactive Streams protocol on all of its layers.

Canonical example:

```scala
import akka.actor._ // untyped Actor system
import akka.stream.scaladsl.{ Source, Flow, Sink }
implicit val system = ActorSystem()
// The materializer is needed to transform the description of a streaming pipeline (which is eventuallyResult in our example) into an actual running execution of it. Takes the description of the graph, and execute it.
implicit val mat = ActorMaterializer()
/*
We have a source of integers, from one to ten, and then we apply operation, first we multiply them by 2 and finally we run it (everytime we read runX, the pipeline is executed). In this example runFold that takes 0 element and apply sum operation on all the coming elements. So the result of the stream is Future[Integer], is the sum of the multiplied by 2 elements from 1 to 10.
runFold is syntatic sugar and stays for:
.runWith(
Sink.fold(0)((acc: Int, x: Int) => acc + x)
)
*/
val eventuallyResult: Future[Int] =
		Source(1 to 10)
				.map(_ * 2)
				.runFold(0)((acc, x) => acc + x)

// We can actually translate this code in a more modular and compositional one:

val numbers = Source(1 to 10)
val doubling = Flow.fromFunction((x: Int) => x * 2)
val sum = Sink.fold(0)((acc: Int, x: Int) => acc + x)

val eventuallyResult: Future[Int] =
		numbers.via(doubling).runWith(sum)
```

### Shapes of processing stages

In Akka Streams the "steps" of the processing pipeline (the Graph), are referred to as stages. The term operator is used for the fluent DSL's API, such as map, filter etc.

The term Stage is more general, as it also maeans various fan-in and fan-out shapes.

Akka Stream's main shapes we will be dealing with are:

- Source (It is the equivalent of Publisher) - has exactly 1 output. 
- Flow (It is the equivalent of a Processor (publisher and subscribe at the same time), can transform incoming data and emit some other data) - has exactly 1 input, and 1 output.
- Sink (It is the equivalent of Subscriber) - has exactly 1 input.

### **Concurrency is not parallelism**

> In programming, concurrency is the composition of idependently executing processes, while parallelism is the simultaneous execution of (possibly related) computations. Concurrency is about dealing with lots of things at once. Parallelism is about doing lots of things at once - Andrew Gerrand.

Stages are by definition concurrent, yet depending on how they are fused and executed may or may not be running in prallel. To introduce parallelism, use the .async operator, or * Async versions of operators. For example map (0 => 2) can be mapAsync(2)(0 => F[02]) were we tell to the acot materializer to execute this map with 2 operation in parallel at the same time.

## Failure handling and processing rate

**Failure in contrast to Error:**

> **The reactive manifesto defines failures as: A failure is an unexpected event within a service that prevents it from continuing to function normally.**
>
> **And contracsts them with errors: This is in contrast with an error, which is an expected and coded-for condition - for example an error discovered during input validation, that will be communicated to the client as part of the normal processing of the message.**

In Reactive Streams, mostly due to historical naming of such methods in Reactive Extensions, the name onError is used to talk about stream failure. Among other reasons why this should be seen as a failure signal is:

- onError signals can be sent out-of-bounds (no demand, as well as "overtake" onNext signals).
- onError carried failures are not part of the streams' data model, any exception could be thrown at any point in time.
- An onError signal is terminal, if a failure happens and onError is sent, no further signals may be sent by that upstream.

**Carrying errors as values**: While we will indeed use the failure handling mechanisms built-into Akka Streams, another important technique to be aware of is carrying errors as values, e.g. : Flow[Validatable, ValidationResult, _] representing a stream of values where each has to be validated.

**Recovering from failure**: Akka Streams provides a number of ways to recover from failure signals of an upstream, like: recover(pf: PartialFunction [Throwable, T]) operator.

### Processing Rate 

Processing rate is the thorughput at which a stage is processing elements. In streaming systems it is often refered to as a processing stages throughput, which we'll talk about in elements per second (or other time unit).

It is important to realise, that the processing rate, may be different at various points in the stream. For example, imagine stream composed of 3 stages:

- An infinite source of numbers.
- A flow stage, that only emits an element downstream if and only if it is divisible by 2.
- A sink, that accepts such numbers.

We can observe two kinds distinct thorughput values in the system:

- At the source / before the flow.
- After the flow / before the sink.

Some operators may take advantage of being aware of the Reactive Streams back-pressure:

- conflate - combines elements from upstream while downstream back-pressures. Allow for a slower downstream by passing incoming elements and a  summary into an aggregate function as long as there is backpressure. When a fast producer can not be informed to slow down by backpressure or some other signal, `conflate` might be useful to combine elements from a producer until a demand signal comes from a consumer.
- extrapolate - in face of faster downstream, allows extrapolating elements from last seen upstream element. E.g. we have received one element but the downstream is very fast and keep demanding elements from us. We can from this one element we can produce a list of them that will somehow be an extrapolation of the estimate that we have seen in the previous element. Imagine a videoconference client decoding a video feed from a colleague  working remotely. It is possible the network bandwidth is a bit  unreliable. It’s fine, as long as the audio remains fluent, it doesn’t  matter if we can’t decode a frame or two (or more). When a frame is  dropped, though, we want the UI to show the last frame decoded. E.g. one implementation: if upstream is too slow, produce copies of the last frame but grayed out.
