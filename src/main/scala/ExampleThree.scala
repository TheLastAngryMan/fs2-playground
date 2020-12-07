import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import sun.misc.{Signal, SignalHandler}

import java.time.Instant
import scala.concurrent.duration._


object ExampleThree extends IOApp {
  def createInfiniteStream(number: Int, interrupter: SignallingRef[IO, Boolean]): Stream[IO, Int] = {
    Stream.awakeEvery[IO](1.second).zipRight(Stream.emit(number)).evalTap(i => IO(println(i))).repeat.interruptWhen(interrupter)
  }

  def addSignalHook(queue: Queue[IO, Signals]): Unit = {
    Signal.handle(new Signal("USR2"), _ => queue.enqueue1(Reload(10)).unsafeRunSync())
  }

  override def run(args: List[String]): IO[ExitCode] = {
    (for {
      queue <- Queue.unbounded[IO, Signals]
      _ = addSignalHook(queue)
      _ <- SignallingRef[IO, Boolean](false)
      _ <- queue.dequeue.evalTap(_ => IO(println(s"Received Signal"))).compile.drain
    } yield ()).as(ExitCode.Success)
  }
}
