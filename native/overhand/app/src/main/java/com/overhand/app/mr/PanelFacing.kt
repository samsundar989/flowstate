package com.overhand.app.mr

/** Panel forward points away from its readable face. Cull the whole rear surface. */
object PanelFacing {
  fun front(nx: Float,ny: Float,nz: Float,ex: Float,ey: Float,ez: Float) = nx*ex+ny*ey+nz*ez < 0f
}
