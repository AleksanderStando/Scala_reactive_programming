package EShop.lab2

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import scala.language.postfixOps
import scala.concurrent.duration._
import EShop.lab3.OrderManager

object TypedCartActor {
  sealed trait Command
  case class AddItem(item: Any)                                                  extends Command
  case class RemoveItem(item: Any)                                               extends Command
  case object ExpireCart                                                         extends Command
  case class StartCheckout(orderManagerRef: akka.actor.ActorRef) extends Command
  case object ConfirmCheckoutCancelled                                           extends Command
  case object ConfirmCheckoutClosed                                              extends Command
  case class GetItems(sender: ActorRef[Cart])                                    extends Command // command made to make testing easier

  sealed trait Event
  case class CheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command]) extends Event
}

class TypedCartActor {
  import TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5 seconds

  private def scheduleTimer(context: ActorContext[TypedCartActor.Command]): Cancellable = {
    context.scheduleOnce(cartTimerDuration, context.self, ExpireCart)
  }

  def start: Behavior[TypedCartActor.Command] = empty

  def empty: Behavior[TypedCartActor.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case AddItem(item) =>
          nonEmpty(Cart(Seq(item)), scheduleTimer(context))

        case GetItems(sender) =>
          sender ! Cart.empty
          Behaviors.same
      }
  )

  def nonEmpty(cart: Cart, timer: Cancellable): Behavior[TypedCartActor.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case AddItem(item) =>
          timer.cancel()
          nonEmpty(cart.addItem(item), scheduleTimer(context))

        case GetItems(sender) =>
          sender ! cart
          Behaviors.same

        case RemoveItem(item) if cart.contains(item) && cart.size > 1 =>
          timer.cancel()
          nonEmpty(cart.removeItem(item), scheduleTimer(context))

        case RemoveItem(item) if cart.contains(item) =>
          timer.cancel()
          empty

        case StartCheckout(orderManagerRef) =>
          timer.cancel()
          val checkout = context.spawn(new TypedCheckout(context.self).start, "checkout")
          orderManagerRef ! TypedCartActor.CheckoutStarted(checkout)
          checkout ! TypedCheckout.StartCheckout
          inCheckout(cart)

        case ExpireCart =>
          timer.cancel()
          empty
      }
  )

  def inCheckout(cart: Cart): Behavior[TypedCartActor.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case ConfirmCheckoutCancelled =>
          nonEmpty(cart, scheduleTimer(context))

        case ConfirmCheckoutClosed =>
          empty

        case GetItems(sender) =>
          sender ! Cart.empty
          Behaviors.same
      }
  )
}
