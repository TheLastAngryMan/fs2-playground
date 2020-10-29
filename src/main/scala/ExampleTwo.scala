import java.time.Instant

import cats.effect.{Concurrent, ConcurrentEffect, ExitCode, IO, IOApp, Timer}
import fs2.{Pipe, Stream}
import fs2.concurrent.{Queue, SignallingRef}
import sun.misc.{Signal, SignalHandler}

import scala.concurrent.duration._

class ControlStream2(queue: Queue[IO, Signals], interrupter: SignallingRef[IO, Boolean])(implicit F: Concurrent[IO], timer: Timer[IO]) {
  def createInfiniteStream(number: Int): Stream[IO, Int] = {
    Stream.awakeEvery[IO](1.second).zipRight(Stream.emit(number)).evalTap(i => IO(println(i))).repeat.interruptWhen(interrupter)
  }

  def subscribe: Stream[IO, Unit] = {
    def processEvent: Pipe[IO, Signals, Unit] = pipe => pipe.flatMap {
      case Reload(n) =>
        for {
          _ <- Stream.eval(interrupter.set(true))
          _ <- Stream.eval(interrupter.set(false))
          _ <- pipe.concurrently(createInfiniteStream(n))
        } yield ()
    }
    queue.dequeue.through(processEvent)
  }
}

object ExampleTwo extends IOApp{
  def addSignalHook[F[_]](queue: Queue[F, Signals])(implicit F: ConcurrentEffect[F]): Stream[F, SignalHandler] = Stream.eval {
    F.delay {
      def enqueue(): Unit =
        F.runAsync({
          queue.enqueue1(Reload((Instant.now.getEpochSecond % 100).toInt))
        })(_ => IO.unit)
          .unsafeRunSync()
      Signal.handle(new Signal("USR2"), _ => enqueue())
    }
  }

  val program: Stream[IO, Unit] = for {
    queue <- Stream.eval(Queue.circularBuffer[IO, Signals](10))
    signal <- Stream.eval(SignallingRef[IO, Boolean](false))
    _ <- addSignalHook(queue)
    _ <- Stream.eval(queue.enqueue1(Reload(1)))
    service = new ControlStream2(queue, signal)
    _ <- service.subscribe
  } yield ()

  override def run(args: List[String]): IO[ExitCode] = program.compile.drain.as(ExitCode.Success)
}
