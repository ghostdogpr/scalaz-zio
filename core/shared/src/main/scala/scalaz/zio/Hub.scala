package scalaz.zio

import scalaz.zio.stream.Stream

trait Hub[A] {

  /**
    * Publishes a single item of type `A`.
    * Returns true if there was at least one subscriber, otherwise false.
    */
  def publish(a: A): UIO[Boolean]

  /**
    * Subscribes to items published that match the condition `cond`, executing `f` on each item.
    * `queueSize` defines how many items should be buffered while waiting to be consumed. `None` means no limit.
    * Returns a fiber that can be interrupted to stop listening.
    */
  def subscribe[R, E](queueSize: Option[Int],
                      cond: A => Boolean,
                      f: A => ZIO[R, E, Unit]): ZIO[R, Nothing, Fiber[E, Nothing]]

  /**
    * Subscribes to all items published, executing `f` on each item.
    * `queueSize` defines how many items should be buffered while waiting to be consumed. `None` means no limit.
    * Returns a fiber that can be interrupted to stop listening.
    */
  def subscribeAll[R, E](queueSize: Option[Int], f: A => ZIO[R, E, Unit]): ZIO[R, Nothing, Fiber[E, Nothing]] =
    subscribe(queueSize, _ => true, f)

  /**
    * Publishes all items from a stream into the hub.
    * Returns a fiber that can be interrupted to stop publishing.
    */
  def publishFromStream[R, E](stream: Stream[R, E, A]): ZIO[R, Nothing, Fiber[E, Unit]] =
    stream.foreach(publish(_).void).fork

  /**
    * Publishes all items from a queue into the hub.
    * Returns a fiber that can be interrupted to stop publishing.
    */
  def publishFromQueue(queue: Queue[A]): UIO[Fiber[Nothing, Nothing]] =
    queue.take.flatMap[Any, Nothing, Boolean](publish).forever.fork

}

object Hub {

  def apply[A](): UIO[Hub[A]] =
    for {
      state <- Ref.make(List[(Queue[A], A => Boolean)]())
    } yield
      new Hub[A] {
        override def publish(a: A): UIO[Boolean] =
          for {
            subscribers <- state.get.map(_.collect { case (queue, cond) if cond(a) => queue })
            _           <- IO.foreachPar(subscribers)(_.offer(a))
          } yield subscribers.nonEmpty

        override def subscribe[R, E](queueSize: Option[Int],
                                     cond: A => Boolean,
                                     f: A => ZIO[R, E, Unit]): ZIO[R, Nothing, Fiber[E, Nothing]] =
          queueSize
            .fold(Queue.unbounded[A])(Queue.bounded[A]) // TODO support all strategies
            .flatMap(
            queue =>
              state.update((queue, cond) :: _) *> queue.take
                .flatMap(f)
                .forever
                .onInterrupt(state.update(_.filterNot(_._1 == queue)))
                .fork
          )
      }

}
