# Asynchronous Programming

Asynchronous Execution = Execution of a computation on another computing unit, without waiting for its termination => Better resource efficiency.

## Futures

From the scala-lang.org “Futures and Promises” page provides this summary:

“The idea is simple: a Future is a sort of a placeholder object that you can create for a result that does not yet exist. Generally, the result of the Future is computed concurrently and can be later collected. Composing concurrent tasks in this way tends to result in faster, asynchronous, non-blocking parallel code. A Future is an object holding a value which may become available at some point.”

Here are some key points about futures:

- A future represents the result of an asynchronous computation, and has a return type, such as Future[Int].
- The value in a future is always an instance of Try, so you always deal with Success and Failure when handling a future’s result.
-  You typically work with the results of a future using its callback methods, such as onComplete.
- A future is a monad, and can be composed. It has combinator methods like map, flatMap, filter, etc.
- There’s no guarantee that your future’s callback method will be called on thesame thread the future was run on.

Considering the following snippet:

```scala
def makeCoffe(): Future[Coffe] = ...

def coffeBreak(): Unit = {
  val eventuallyCoffe = makeCoffe()
  eventuallyCoffe.onComplete { 
    case Success(coffe) => drink(coffe)
    case Failure(reason) => ...
  }
  chatWithColleagues()
}
```

### Map/ FlatMap operations on Future

```scala
trait Future[+A]{
  def map[B](f: A => B): Future[B]
}
```

- Transforms a successful Future[A] into a Future[B] by applying a function f: A => B after the Future[A] as completed.
- Automatically propagates the failure on the former Future[A] (if any), to the resulting Future[B].

E.g.

```scala
def grindBeans(): Future[GroundCoffe]
def brew(groundCoffe: GroundCoffe): Coffe

def makeCoffe(): Future[Coffe] =
	grindBeans().map(groundCoffe => brew(groundCoffe))

/* Look makeCoffe returns Future[C], 
but if we had 
def brew(groundCoffe: GroundCoffe): Future[Coffe] (Async operation) 
the result would have been Future[Future[C]] 
For this case we have flatMap*/

def brew(groundCoffe: GroundCoffe): Future[Coffe]

def makeCoffe(): Future[Coffe] =
	grindBeans().flatMap(groundCoffe => brew(groundCoffe))
```

### Zip operation on Futures

```scala
trait Future[+A] {
  def zip[B](other: Future[B]: Future[(A, B)])
}
```

- Joins two successful Future[A] and Future[B] values into a single successful Future[(A, B)] value.
- Returns a failure if any of the two Future values failed.
- Does not create any dependency between the two Future values.

E.g.

```scala
// the two makeCoffe are concurrently evaluated
def makeTwoCoffes(): Future[(Coffe, Coffe)] = 
		makeCoffe() zip makeCoffe()
```

### For expression with Futures

```scala
// Each line is evaluated only after the previous one is completed.
def workRoutine(): Future[Work] =
	for {
    work1 <- work()
    _ 		<- coffeBreak()
    work2 <- work()
  } yield work1 + work2
```

### Recover and RecoverWith Operations on Future

Turn a failed Future into a successful one:

- Recover takes as parameter a partial function that may turn in a failure, or to a succesful value.
- Recover does the same but the succesful function is a Future, so asynchronous.

```scala
grindBeans()
	.recoverWith {
    case BeansBucketEmpty => 
    		refillBeans.flatMap( _ => grindBeans())
  }
	.flatMap(coffePowder => brew(CoffePowder))
```

## Execution Context

Where we perform an asynchronous call? The general answer is that depends of the underlying system. For instance user can chose to allocate a single thread to execute all the asynchrnous operations (no parallelism) or can chose to allocate a fixed size thread pool (parallelism). 

Usually we import at beginning of the program the execution context that you wanna use, and then don't worry about it (implicitly passed to functions):

```scala
// this import uses a thread pool with as many thread as the underline system can handle.
import scala.concurrent.ExecutionContext.implicits.global

trait Future[+A]{
  def onComplete(k: Try[A] => Unit)(implicit ec: ExecutionContext): Unit
}
```



