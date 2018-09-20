package org.akkajs.sbt

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import scala.scalajs.js

import com.definitelyscala.node.Buffer
import com.definitelyscala.node.net.Socket

class SocketClient(
    sock: Socket
) {

  val inFlight = mutable.Map[String, Promise[Event]]()

  var buffer: String = ""

  sock.on(
    "data",
    (data: js.Dynamic) => {
      val str = data.toString()
      buffer = buffer + str
      parseBuffer()
    }
  )

  def parseBuffer(): Unit = {
    if (buffer.contains("Content-Length:")) {
      val start = buffer.indexOf('{')
      val init = buffer.substring(start, buffer.length)

      val next = init.indexOfSlice("Content-Length:")
      val msg =
        if (next == -1) init
        else init.substring(0, next)

      def trimBuffer() = {
        if (next == -1) buffer = ""
        else buffer = buffer.substring(init.length + next, buffer.length)
      }

      try {
        val json = js.JSON.parse(msg)
        try {
          onMessage(json)
        } catch {
          case err: Throwable =>
            CliLogger.logger.error(s"Unhandled message ${msg}.")
        }
        trimBuffer()
        parseBuffer()
      } catch {
        case _: Throwable =>
        //not yet a valid json message nothing to do
      }
    }
  }

  def onMessage(msg: js.Dynamic) = {
    // println("message: "+js.JSON.stringify(msg))
    val id = msg.id.toString

    inFlight.get(id) match {
      case Some(prom) =>
        inFlight -= id
        prom.success(Result(msg))
      case _ if (!js.isUndefined(msg.method)) =>
        onNotification(msg)
      case _ =>
        CliLogger.logger.error(s"unmatched message from server")
        CliLogger.logger.trace(js.JSON.stringify(msg))
    }
  }

  def onNotification(json: js.Dynamic): Unit = {
    json.method.toString match {
      case "window/logMessage" =>
        val content = json.params
        SbtLogger.log(content.message.toString, content.`type`.toString.toInt)
      case "textDocument/publishDiagnostics" =>
        println(s"To be done \n ${js.JSON.stringify(json)}")
    }
  }

  def send(cmd: Command): Future[Event] = {
    val answer = Promise[Event]()
    val id = cmd.execId.toString
    inFlight += (id -> answer)
    rawSend(cmd)
    answer.future
  }

  private def rawSend(cmd: Command) = {
    val serialized = cmd.serialize()
    val str = s"Content-Length: ${serialized.length + 2}\r\n\r\n$serialized\r\n"
    sock.write(Buffer.from(str, "UTF-8"))
  }
}