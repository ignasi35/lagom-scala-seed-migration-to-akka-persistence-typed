package org.eggsample.smellostream.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.server._
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import play.api.libs.ws.ahc.AhcWSComponents
import org.eggsample.smellostream.api.SmelloStreamService
import org.eggsample.smello.api.SmelloService
import com.softwaremill.macwire._

class SmelloStreamLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new SmelloStreamApplication(context) {
      override def serviceLocator: NoServiceLocator.type = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new SmelloStreamApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[SmelloStreamService])
}

abstract class SmelloStreamApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with AhcWSComponents {

  // Bind the service that this server provides
  override lazy val lagomServer: LagomServer = serverFor[SmelloStreamService](wire[SmelloStreamServiceImpl])

  // Bind the SmelloService client
  lazy val smelloService: SmelloService = serviceClient.implement[SmelloService]
}
