package com.overhand.app.graph

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * t775 off-device mirror of GraphScene: the same mesh geometry (tiles, ring segments, icon strokes, edge paths) and
 * the same GraphLod items and alpha rule, drawn orthographically for a viewer [distance] metres in front of the
 * focus point at [zoom]. Writes a PNG and the layout-lint element JSON (one element per node box and per text line).
 * Text3D widths use the headset calibration LayoutLintCapture documents (0.43 em advance, "Replay th" = 3.89 em).
 */
object GraphRender {
  data class Out(val bitmap: Bitmap,val meta: JSONObject)

  fun render(name: String,model: GraphModel,infos: Map<String,GraphLod.NodeInfo>,focus: String?,zoom: Double,distance: Double,
    widthM: Double?=null,heightM: Double?=null,pxPerM: Double=960.0,offset: Pair<Double,Double> =0.0 to 0.0): Out {
    val items=GraphLod.items(model,infos,0,focus)
    val visible=model.nodes.take(GraphLayout.PAGE_SIZE)
    val f=focus?.let { id -> visible.first { it.id==id } }
    // Viewport in graph-local metres (zoom 1); world metres = local * zoom.
    val margin=0.12
    val (minX,maxX,minY,maxY)=if(f==null || widthM==null || heightM==null) listOf(visible.minOf { it.x }-GraphLayout.WIDTH/2-margin,
      visible.maxOf { it.x }+GraphLayout.WIDTH/2+margin,visible.minOf { it.y }-GraphLayout.HEIGHT/2-margin,visible.maxOf { it.y }+GraphLayout.HEIGHT/2+margin)
      else listOf(f.x+offset.first-widthM/zoom/2,f.x+offset.first+widthM/zoom/2,f.y+offset.second-heightM/zoom/2,f.y+offset.second+heightM/zoom/2)
    val scale=pxPerM*zoom
    val w=((maxX-minX)*scale).roundToInt();val h=((maxY-minY)*scale).roundToInt()
    fun px(x: Double)=((x-minX)*scale).toFloat()
    fun py(y: Double)=((maxY-y)*scale).toFloat()
    fun c(a: FloatArray,alpha: Float=1f)=Color.argb((alpha*255).roundToInt(),(a[0]*255).roundToInt(),(a[1]*255).roundToInt(),(a[2]*255).roundToInt())
    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(16,20,24))
    val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND }
    val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    // Edges (behind the tiles), with the data-flow arrowhead.
    val byId=visible.associateBy { it.id }
    model.edges.forEachIndexed { i,e -> val a=byId[e.from];val b=byId[e.to]
      if(a!=null && b!=null) { val p=GraphLod.edgePath(a,b,i)
        line.color=c(floatArrayOf(.36f,.56f,.66f,1f));line.strokeWidth=(0.009*scale).toFloat()
        p.zipWithNext().forEach { (u,v) -> canvas.drawLine(px(u.x),py(u.y),px(v.x),py(v.y),line) }
        val end=p.last();canvas.drawLine(px(end.x-0.025),py(end.y+0.018),px(end.x),py(end.y),line);canvas.drawLine(px(end.x-0.025),py(end.y-0.018),px(end.x),py(end.y),line)
      } }
    val elements=JSONArray()
    elements.put(JSONObject().put("index",0).put("parent",-1).put("name","architecture-scene").put("class","GraphScene")
      .put("rect",JSONArray(listOf(0,0,w,h))).put("visible_rect",JSONArray(listOf(0,0,w,h))).put("clickable",false))
    val boxIndex=mutableMapOf<String,Int>()
    visible.forEach { n -> val info=infos[n.id] ?: return@forEach
      if(info.state=="building") { fill.color=c(floatArrayOf(.28f,.65f,.98f),0.45f)
        canvas.drawRoundRect(RectF(px(n.x-GraphLayout.WIDTH/2-0.02),py(n.y+GraphLayout.HEIGHT/2+0.02),px(n.x+GraphLayout.WIDTH/2+0.02),py(n.y-GraphLayout.HEIGHT/2-0.02)),0f,0f,fill) }
      fill.color=c(GraphMesh.TILE)
      val box=RectF(px(n.x-GraphLayout.WIDTH/2),py(n.y+GraphLayout.HEIGHT/2),px(n.x+GraphLayout.WIDTH/2),py(n.y-GraphLayout.HEIGHT/2))
      canvas.drawRect(box,fill)
      val cx=n.x+GraphLod.RING_X;val cy=n.y+GraphLod.RING_Y
      line.color=c(GraphLod.ringColour(info.state));line.strokeWidth=(0.008*scale).toFloat()
      GraphMesh.ringSegments(info.state).forEach { (a0,a1,r) -> canvas.drawLine(px(cx+r*cos(a0)),py(cy+r*sin(a0)),px(cx+r*cos(a1)),py(cy+r*sin(a1)),line) }
      line.color=Color.rgb(242,245,247);line.strokeWidth=(0.006*scale).toFloat();val k=GraphLod.RING_R*0.5
      GraphLabels.icon(info.kind).forEach { s -> canvas.drawLine(px(cx+s[0]*k),py(cy+s[1]*k),px(cx+s[2]*k),py(cy+s[3]*k),line) }
      if(info.state=="building") { fill.color=Color.WHITE
        canvas.drawRect(px(n.x+0.17-0.022),py(n.y-0.115+0.022),px(n.x+0.17+0.022),py(n.y-0.115-0.022),fill) }
      boxIndex[n.id]=elements.length()
      // A zoomed view is a crop: the mirror reports each box as clipped to the view, as the headset frame would.
      val r=listOf(box.left.coerceIn(0f,w.toFloat()),box.top.coerceIn(0f,h.toFloat()),box.right.coerceIn(0f,w.toFloat()),box.bottom.coerceIn(0f,h.toFloat())).map { it.roundToInt() }
      if(r[2]-r[0]<1 || r[3]-r[1]<1) { boxIndex.remove(n.id);return@forEach }
      elements.put(JSONObject().put("index",elements.length()).put("parent",0).put("name","node[${n.id}]").put("class","GraphNodeBox")
        .put("rect",JSONArray(r)).put("visible_rect",JSONArray(r))
        .put("clickable",r[2]-r[0]>=box.width()-1 && r[3]-r[1]>=box.height()-1))   // a crop-cut box is not a target in this view
    }
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.SANS_SERIF }
    items.forEach { item ->
      val a=GraphLod.alpha(item.em,zoom,distance)
      if(a<=0 || item.node !in boxIndex) return@forEach
      paint.textSize=(item.em*scale).toFloat()
      paint.textScaleX=1f;paint.textScaleX=(3.89*item.em*scale).toFloat()/paint.measureText("Replay th")
      paint.color=if(item.level==GraphLod.Level.SYSTEM || item.level==GraphLod.Level.MODULE) Color.argb((a*255).roundToInt(),241,244,247) else Color.argb((a*255).roundToInt(),204,214,224)
      val top=py(item.y);val left=px(item.x)
      val glyphH=((GraphLod.TOP_EM-GraphLod.BOTTOM_EM)*item.em*scale).toFloat()
      val baseline=top+(0.72*item.em*scale).toFloat()   // cap height below the glyph top
      canvas.drawText(item.text,left,baseline,paint)
      val right=left+paint.measureText(item.text)
      val bottom=top+glyphH
      val parent=boxIndex[item.node] ?: 0
      val tile=byId.getValue(item.node)
      val lines=JSONArray().put(JSONObject().put("start",0).put("end",item.text.length).put("text",item.text)
        .put("left",left.toDouble()).put("right",right.toDouble()).put("top",top.toDouble()).put("bottom",bottom.toDouble())
        .put("baseline",baseline.toDouble()).put("ellipsis",0))
      if(right<0 || left>w || bottom<0 || top>h) return@forEach   // outside the cropped view
      if(left<0 || right>w || top<0 || bottom>h) return@forEach     // cut by the crop edge, not by the layout
      val rect=listOf(left,top,right,bottom).map { it.roundToInt() }
      elements.put(JSONObject().put("index",elements.length()).put("parent",parent).put("name","${item.level.name.lowercase()}[${item.node}]'${item.text}'")
        .put("class","Text3D").put("text",item.text).put("text_size_px",paint.textSize.toDouble()).put("text_color","#FFF1F4F7")
        .put("rect",JSONArray(rect)).put("visible_rect",JSONArray(rect)).put("inner",JSONArray(listOf(left,top,right,bottom).map { it.toDouble() }))
        .put("lines",lines).put("chars_dropped",0).put("clickable",false).put("gravity_h","start").put("alpha",a)
        .put("background_rgb",JSONArray(GraphMesh.TILE.take(3).map { (it*255).roundToInt() }))
        .put("tile",tile.id))
    }
    val meta=JSONObject().put("window",name).put("kind","scene-mirror").put("source","GraphScene (t775: GraphLod items + GraphMesh tiles)")
      .put("width_px",w).put("height_px",h).put("m_per_px",1.0/pxPerM)
      .put("distance_m",distance).put("zoom",zoom).put("grid",false).put("density",JSONObject.NULL).put("elements",elements)
    return Out(bitmap,meta)
  }
}
