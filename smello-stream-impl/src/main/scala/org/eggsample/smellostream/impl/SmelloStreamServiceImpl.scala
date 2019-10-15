package org.eggsample.smellostream.impl

import com.lightbend.lagom.scaladsl.api.ServiceCall
import org.eggsample.smellostream.api.SmelloStreamService
import org.eggsample.smello.api.SmelloService

import scala.concurrent.Future

/**
  * Implementation of the SmelloStreamService.
  */
class SmelloStreamServiceImpl(smelloService: SmelloService) extends SmelloStreamService {
  def stream = ServiceCall { hellos =>
    Future.successful(hellos.mapAsync(8)(smelloService.hello(_).invoke()))
  }
}
