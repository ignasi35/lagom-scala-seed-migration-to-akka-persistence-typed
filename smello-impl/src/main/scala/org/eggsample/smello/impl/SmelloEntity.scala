package org.eggsample.smello.impl

import play.api.libs.json.Json
import play.api.libs.json.Format
import java.time.LocalDateTime

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl._
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect
import com.lightbend.lagom.scaladsl.persistence.AggregateEvent
import com.lightbend.lagom.scaladsl.persistence.AggregateEventTag
import com.lightbend.lagom.scaladsl.persistence.AkkaTaggerAdapter
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import play.api.libs.json._

import scala.collection.immutable.Seq

/**
  * This provides an event sourced behavior. It has a state, [[SmelloState]], which
  * stores what the greeting should be (eg, "Hello").
  *
  * Event sourced entities are interacted with by sending them commands. This
  * entity supports two commands, a [[UseGreetingMessage]] command, which is
  * used to change the greeting, and a [[Hello]] command, which is a read
  * only command which returns a greeting to the name specified by the command.
  *
  * Commands get translated to events, and it's the events that get persisted by
  * the entity. Each event will have an event handler registered for it, and an
  * event handler simply applies an event to the current state. This will be done
  * when the event is first created, and it will also be done when the entity is
  * loaded from the database - each event will be replayed to recreate the state
  * of the entity.
  *
  * This entity defines one event, the [[GreetingMessageChanged]] event,
  * which is emitted when a [[UseGreetingMessage]] command is received.
  */
object SmelloBehavior {


  def create(entityContext: EntityContext): Behavior[SmelloCommand[_]] = {
    val persistenceId: PersistenceId = SmelloState.typeKey.persistenceIdFrom(entityContext.entityId)

    create(persistenceId)
      .withTagger(
        // Using Akka Persistence Typed in Lagom requires tagging your events
        // in Lagom-compatible way so Lagom ReadSideProcessors and TopicProducers
        // can locate and follow the event streams.
        AkkaTaggerAdapter.fromLagom(entityContext, SmelloEvent.Tag)
      )

  }
  private[eggsample] def create(persistenceId: PersistenceId) = EventSourcedBehavior
      .withEnforcedReplies[SmelloCommand[_], SmelloEvent, SmelloState](
        persistenceId = persistenceId,
        emptyState = SmelloState.initial,
        commandHandler = (cart, cmd) => cart.applyCommand(cmd),
        eventHandler = (cart, evt) => cart.applyEvent(evt)
      )
}

/**
  * The current state of the Persistent Entity.
  */
case class SmelloState(message: String, timestamp: String) {
  def applyCommand(
    cmd: SmelloCommand[_]
  ): ReplyEffect[SmelloEvent, SmelloState] =
    cmd match {
      case x: Hello              => onHello(x)
      case x: UseGreetingMessage => onGreetingMessageUpgrade(x)
    }

  def applyEvent(evt: SmelloEvent): SmelloState =
    evt match {
      case GreetingMessageChanged(msg) => updateMessage(msg)
    }
  private def onHello(cmd: Hello): ReplyEffect[SmelloEvent, SmelloState] =
    Effect.reply(cmd)(Greeting(s"$message, ${cmd.name}!"))

  private def onGreetingMessageUpgrade(
    cmd: UseGreetingMessage
  ): ReplyEffect[SmelloEvent, SmelloState] =
    Effect
      .persist(GreetingMessageChanged(cmd.message))
      .thenReply(cmd) { _ =>
        Accepted
      }

  private def updateMessage(newMessage: String) =
    copy(newMessage, LocalDateTime.now().toString)
}

object SmelloState {

  /**
    * The initial state. This is used if there is no snapshotted state to be found.
    */
  def initial: SmelloState = SmelloState("Hello", LocalDateTime.now.toString)

  /**
    * The Event Sourced Behavior instances run on sharded actors inside the Akka Cluster.
    * When sharding actors and distributing them across the cluster, each entity is
    * namespaced under a typekey that specifies a name and also the type of the commands
    * that sharded actor can receive.
    */
  val typeKey = EntityTypeKey[SmelloCommand[_]]("SmelloStateEntity")

  /**
    * Format for the hello state.
    *
    * Persisted entities get snapshotted every configured number of events. This
    * means the state gets stored to the database, so that when the entity gets
    * loaded, you don't need to replay all the events, just the ones since the
    * snapshot. Hence, a JSON format needs to be declared so that it can be
    * serialized and deserialized when storing to and from the database.
    */
  implicit val format: Format[SmelloState] = Json.format
}

/**
  * This interface defines all the events that the SmelloEntity supports.
  */
sealed trait SmelloEvent extends AggregateEvent[SmelloEvent] {
  def aggregateTag: AggregateEventTag[SmelloEvent] = SmelloEvent.Tag
}

object SmelloEvent {
  val Tag: AggregateEventTag[SmelloEvent] = AggregateEventTag[SmelloEvent]
}

/**
  * An event that represents a change in greeting message.
  */
case class GreetingMessageChanged(message: String) extends SmelloEvent

object GreetingMessageChanged {

  /**
    * Format for the greeting message changed event.
    *
    * Events get stored and loaded from the database, hence a JSON format
    * needs to be declared so that they can be serialized and deserialized.
    */
  implicit val format: Format[GreetingMessageChanged] = Json.format
}

/**
  * This is a marker trait for commands.
  * We will serialize them using Akka's Jackson support that is able to deal with the replyTo field.
  * (see application.conf)
  */
trait SmelloCommandSerializable

/**
  * This interface defines all the commands that the SmelloEntity supports.
  */
sealed trait SmelloCommand[R <: SmelloReply]
    extends ExpectingReply[R]
    with SmelloCommandSerializable

/**
  * A command to switch the greeting message.
  *
  * It has a reply type of [[Confirmation]], which is sent back to the caller
  * when all the events emitted by this command are successfully persisted.
  */
case class UseGreetingMessage(message: String, replyTo: ActorRef[Confirmation])
    extends SmelloCommand[Confirmation]

/**
  * A command to say hello to someone using the current greeting message.
  *
  * The reply type is String, and will contain the message to say to that
  * person.
  */
case class Hello(name: String, replyTo: ActorRef[Greeting])
    extends SmelloCommand[Greeting]

sealed trait SmelloReply

object SmelloReply {
  implicit val format: Format[SmelloReply] =
    new Format[SmelloReply] {

      override def reads(json: JsValue): JsResult[SmelloReply] = {
        if ((json \ "state").isDefined)
          Json.fromJson[Greeting](json)
        else
          Json.fromJson[Confirmation](json)
      }

      override def writes(o: SmelloReply): JsValue = {
        o match {
          case conf: Confirmation => Json.toJson(conf)
          case state: Greeting    => Json.toJson(state)
        }
      }
    }
}

final case class Greeting(message: String) extends SmelloReply

object Greeting {
  implicit val format: Format[Greeting] = Json.format
}

sealed trait Confirmation extends SmelloReply

case object Confirmation {
  implicit val format: Format[Confirmation] = new Format[Confirmation] {
    override def reads(json: JsValue): JsResult[Confirmation] = {
      if ((json \ "reason").isDefined)
        Json.fromJson[Rejected](json)
      else
        Json.fromJson[Accepted](json)
    }

    override def writes(o: Confirmation): JsValue = {
      o match {
        case acc: Accepted => Json.toJson(acc)
        case rej: Rejected => Json.toJson(rej)
      }
    }
  }
}

sealed trait Accepted extends Confirmation

case object Accepted extends Accepted {
  implicit val format: Format[Accepted] =
    Format(Reads(_ => JsSuccess(Accepted)), Writes(_ => Json.obj()))
}

case class Rejected(reason: String) extends Confirmation

object Rejected {
  implicit val format: Format[Rejected] = Json.format
}

/**
  * Akka serialization, used by both persistence and remoting, needs to have
  * serializers registered for every type serialized or deserialized. While it's
  * possible to use any serializer you want for Akka messages, out of the box
  * Lagom provides support for JSON, via this registry abstraction.
  *
  * The serializers are registered here, and then provided to Lagom in the
  * application loader.
  */
object SmelloSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    // state and events can use play-json, but commands should use jackson because of ActorRef[T] (see application.conf)
    JsonSerializer[GreetingMessageChanged],
    JsonSerializer[SmelloState],
    // the replies use play-json as well
    JsonSerializer[SmelloReply],
    JsonSerializer[Greeting],
    JsonSerializer[Confirmation],
    JsonSerializer[Accepted],
    JsonSerializer[Rejected]
  )
}
