package com.virtuslab.blackmesa.visualization

import akka.NotUsed
import akka.actor.{ ActorSystem, PoisonPill, Props }
import akka.http.scaladsl.model.ws.{ BinaryMessage, Message, TextMessage }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.{ Materializer, OverflowStrategy }
import com.virtuslab.blackmesa.visualization.ModelActor.IncomingConnection
import com.virtuslab.blackmesa.visualization.Protocol.{ ClientMessage, InvalidMessage, ServerMessage }

import scala.util.Try

trait ActorBasedModelExecution extends JsonSupport {

  import spray.json._

  implicit def system: ActorSystem

  implicit def materializer: Materializer

  protected def configuration: Configuration

  def startNewModelActorFlow: Flow[Message, Message, NotUsed] = {
    // LBIALY TODO add supervisor probably, this is a one-shot and will die on failure
    val modelActor = system.actorOf(Props(new ModelActor(configuration)))

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].map {
        case TextMessage.Strict(text) =>
          Try(text.parseJson.convertTo[ClientMessage])
            .getOrElse(InvalidMessage(text))

        case TextMessage.Streamed(source) =>
          source.runWith(Sink.ignore)
          InvalidMessage("streamed")

        case bm: BinaryMessage =>
          bm.dataStream.runWith(Sink.ignore)
          InvalidMessage("binary")

      }.to(Sink.actorRef[ClientMessage](modelActor, PoisonPill))

    // LBIALY TODO change OverflowStrategy, this is data-unsafe, but won't crash
    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[ServerMessage](30, OverflowStrategy.dropNew)
        .mapMaterializedValue { outActor =>
          modelActor ! IncomingConnection(outActor)
          NotUsed
        }
        .map {
          outgoingMessage => TextMessage(outgoingMessage.toJson.compactPrint)
        }

    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }

}
