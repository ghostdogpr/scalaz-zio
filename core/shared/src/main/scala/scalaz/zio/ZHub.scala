package scalaz.zio

import scalaz.zio.ZHub.Strategy
import scalaz.zio.ZHub.Strategy._
import scalaz.zio.stream.Stream

/**
 * A `ZHub[I, O]` is a data structure for asynchronously publishing items of type `I` and subscribing to items of type `O`.
 * Unlike `Queue`, every published item is broadcasted to all the subscribers registered at the moment of the publication.
 */
trait ZHub[-I, +O] extends Serializable { self =>

  /**
   * Publishes a single item of type `A`.
   * Returns true if there was at least one subscriber, otherwise false.
   */
  def publish(i: I): UIO[Boolean]

  /**
   * Subscribes to all items from the hub.
   * `strategy` defines how many items should be buffered while waiting to be consumed and how to handle overflow.
   * Use `Unbounded` to keep all items.
   * Use `Bounded` to limit the number of buffered items and apply back pressure on the publishers.
   * Use `Sliding` to limit the number of buffered items and lose the oldest items in case of overflow.
   * Use `Dropping` to limit the number of buffered items and lose the newest items in case of overflow.
   */
  def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, O]]

  /**
   * Transforms elements coming out of this hub with a pure function.
   */
  def map[O1](f: O => O1): ZHub[I, O1] = new ZHub[I, O1] {
    override def publish(i: I): UIO[Boolean] = self.publish(i)
    override def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, O1]] =
      self.subscribe(strategy).map(_.map(f))
  }

  /**
   * Transforms elements coming out of this hub with an effectful function.
   */
  def mapM[O1](f: O => UIO[O1]): ZHub[I, O1] = new ZHub[I, O1] {
    override def publish(i: I): UIO[Boolean] = self.publish(i)
    override def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, O1]] =
      self.subscribe(strategy).map(_.mapM(f))
  }

  /**
   * Transforms elements published into this hub with a pure function.
   */
  def contramap[I1](f: I1 => I): ZHub[I1, O] = new ZHub[I1, O] {
    override def publish(i: I1): UIO[Boolean] = self.publish(f(i))
    override def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, O]] =
      self.subscribe(strategy)
  }

  /**
   * Transforms elements published into this hub with an effectful function.
   */
  def contramapM[I1](f: I1 => UIO[I]): ZHub[I1, O] = new ZHub[I1, O] {
    override def publish(i: I1): UIO[Boolean] = f(i) >>= self.publish
    override def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, O]] =
      self.subscribe(strategy)
  }
}

object ZHub {

  sealed trait Strategy
  object Strategy {
    case object Unbounded               extends Strategy
    case class Bounded(queueSize: Int)  extends Strategy
    case class Sliding(queueSize: Int)  extends Strategy
    case class Dropping(queueSize: Int) extends Strategy
  }

  /**
   * Makes a new hub for items of type `A`.
   */
  def make[A](): UIO[Hub[A]] =
    for {
      state <- Ref.make(List[Queue[A]]())
    } yield
      new Hub[A] {
        override def publish(a: A): UIO[Boolean] =
          for {
            subscribers <- state.get
            sent        <- IO.foreachPar(subscribers)(_.offer(a)).map(_.exists(identity))
          } yield subscribers.nonEmpty && sent

        def createQueue(strategy: Strategy): UIO[Queue[A]] = strategy match {
          case Unbounded      => Queue.unbounded[A]
          case Bounded(size)  => Queue.bounded[A](size)
          case Sliding(size)  => Queue.sliding[A](size)
          case Dropping(size) => Queue.dropping[A](size)
        }

        override def subscribe(strategy: Strategy): Managed[Nothing, Stream[Nothing, A]] =
          for {
            queue <- Managed.fromEffect(createQueue(strategy))
            stream <- Managed.make(state.update(queue :: _).const(Stream.fromQueue(queue)))(
                       _ => state.update(_.filterNot(_ == queue))
                     )
          } yield stream
      }

}
