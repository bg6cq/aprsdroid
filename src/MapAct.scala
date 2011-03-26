package org.aprsdroid.app

import _root_.android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import _root_.android.graphics.drawable.{Drawable, BitmapDrawable}
import _root_.android.graphics.{Canvas, Paint, Path, Point, Rect, Typeface}
import _root_.android.os.{Bundle, Handler}
import _root_.android.util.Log
import _root_.android.view.{Menu, MenuItem, View}
import _root_.com.google.android.maps._

// to make scala-style iterating over arraylist possible
import scala.collection.JavaConversions._

class MapAct extends MapActivity {
	val TAG = "MapAct"

	lazy val prefs = new PrefsWrapper(this)
	lazy val uihelper = new UIHelper(this, R.id.map, prefs)
	lazy val mapview = findViewById(R.id.mapview).asInstanceOf[MapView]
	lazy val allicons = this.getResources().getDrawable(R.drawable.allicons)
	lazy val db = StorageDatabase.open(this)
	lazy val staoverlay = new StationOverlay(allicons, this, db)

	var showObjects = false

	lazy val locReceiver = new LocationReceiver(new Handler(), () => {
			Benchmark("loadDb") {
				staoverlay.loadDb(showObjects)
			}
			mapview.invalidate()
			animateToCall()
			//postlist.setSelection(0)
		})

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.mapview)
		mapview.setBuiltInZoomControls(true)

		staoverlay.loadDb(showObjects)
		animateToCall()
		mapview.getOverlays().add(staoverlay)

		// listen for new positions
		registerReceiver(locReceiver, new IntentFilter(AprsService.UPDATE))

	}

	override def onDestroy() {
		super.onDestroy()
		unregisterReceiver(locReceiver)
	}
	override def isRouteDisplayed() = false

	override def onCreateOptionsMenu(menu : Menu) : Boolean = {
		getMenuInflater().inflate(R.menu.options_map, menu);
		true
	}
	override def onPrepareOptionsMenu(menu : Menu) = uihelper.onPrepareOptionsMenu(menu)

	override def onOptionsItemSelected(mi : MenuItem) : Boolean = {
		mi.getItemId match {
		case R.id.objects =>
			mi.setChecked(!mi.isChecked())
			showObjects = mi.isChecked()
			staoverlay.loadDb(showObjects)
			mapview.invalidate()
			true
		case R.id.satellite =>
			mi.setChecked(!mi.isChecked())
			mapview.setSatellite(mi.isChecked())
			true
		case _ => uihelper.optionsItemAction(mi)
		}
	}

	def animateToCall() {
		val i = getIntent()
		if (i != null && i.getStringExtra("call") != null) {
			val targetcall = i.getStringExtra("call")
			val cursor = db.getStaPositions(targetcall, "1")
			if (cursor.getCount() > 0) {
				cursor.moveToFirst()
				val lat = cursor.getInt(StorageDatabase.Position.COLUMN_LAT)
				val lon = cursor.getInt(StorageDatabase.Position.COLUMN_LON)
				mapview.getController().animateTo(new GeoPoint(lat, lon))
			}
			cursor.close()
			
		}

	}
}

class Station(val point : GeoPoint, val call : String, val message : String, val symbol : String)
	extends OverlayItem(point, call, message) {


}

class StationOverlay(icons : Drawable, context : Context, db : StorageDatabase) extends ItemizedOverlay[Station](icons) {
	val TAG = "StationOverlay"

	//lazy val calls = new scala.collection.mutable.HashMap[String, Boolean]()
	lazy val stations = new java.util.ArrayList[Station]()
	lazy val symbolSize = (context.getResources().getDisplayMetrics().density * 16).toInt

	override def size() = stations.size()
	override def createItem(idx : Int) : Station = stations.get(idx)

	def symbol2rect(symbol : String) : Rect = {
		val alt_offset = if (symbol(0) == '/') 0 else symbolSize*6
		val index = symbol(1) - 32
		val x = (index / 16) * symbolSize + alt_offset
		val y = (index % 16) * symbolSize
		new Rect(x, y, x+symbolSize, y+symbolSize)
	}

	def symbolIsOverlayed(symbol : String) = {
		(symbol(0) != '/' && symbol(0) != '\\')
	}

	def drawTrace(c : Canvas, m : MapView, call : String) : Unit = {
		//Log.d(TAG, "drawing trace of %s".format(call))

		val tracePaint = new Paint()
		tracePaint.setARGB(200, 255, 128, 128)
		tracePaint.setStyle(Paint.Style.STROKE)
		tracePaint.setStrokeJoin(Paint.Join.ROUND)
		tracePaint.setStrokeCap(Paint.Cap.ROUND)
		tracePaint.setStrokeWidth(2)
		tracePaint.setAntiAlias(true)


		val path = new Path()
		val point = new Point()

		val cur = db.getStaPositions(call, "%d".format(System.currentTimeMillis() - 30*3600*1000))
		if (cur.getCount() < 2) {
			cur.close()
			return
		}
		cur.moveToFirst()
		var first = true
		while (!cur.isAfterLast()) {
			val lat = cur.getInt(cur.getColumnIndexOrThrow(StorageDatabase.Position.LAT))
			val lon = cur.getInt(cur.getColumnIndexOrThrow(StorageDatabase.Position.LON))
			m.getProjection().toPixels(new GeoPoint(lat, lon), point)
			if (first) {
				path.moveTo(point.x, point.y)
				first = false
			} else
				path.lineTo(point.x, point.y)
			cur.moveToNext()
		}
		cur.close()
		c.drawPath(path, tracePaint)
	}

	override def draw(c : Canvas, m : MapView, shadow : Boolean) : Unit = {
		if (shadow) return;
		Benchmark("draw") {

		val fontSize = symbolSize*3/4
		val textPaint = new Paint()
		textPaint.setARGB(255, 200, 255, 200)
		textPaint.setTextAlign(Paint.Align.CENTER)
		textPaint.setTextSize(fontSize)
		textPaint.setTypeface(Typeface.MONOSPACE)
		textPaint.setAntiAlias(true)

		val symbPaint = new Paint(textPaint)
		symbPaint.setARGB(255, 255, 255, 255)
		symbPaint.setTextSize(fontSize - 1)

		val strokePaint = new Paint(textPaint)
		strokePaint.setARGB(255, 0, 0, 0)
		strokePaint.setStyle(Paint.Style.STROKE)
		strokePaint.setStrokeWidth(2)

		val symbStrPaint = new Paint(strokePaint)
		symbStrPaint.setTextSize(fontSize - 1)

		val iconbitmap = icons.asInstanceOf[BitmapDrawable].getBitmap

		val p = new Point()
		val proj = m.getProjection()
		val zoom = m.getZoomLevel()
		val (width, height) = (m.getWidth(), m.getHeight())
		val ss = symbolSize/2
		for (s <- stations) {
			proj.toPixels(s.point, p)
			if (p.x >= 0 && p.y >= 0 && p.x < width && p.y < height) {
				val srcRect = symbol2rect(s.symbol)
				val destRect = new Rect(p.x-ss, p.y-ss, p.x+ss, p.y+ss)
				// first draw callsign and trace
				if (zoom >= 10) {
					Benchmark("drawTrace") {
					drawTrace(c, m, s.call)
					}

					c.drawText(s.call, p.x, p.y+ss+fontSize, strokePaint)
					c.drawText(s.call, p.x, p.y+ss+fontSize, textPaint)
				}
				// then the bitmap
				c.drawBitmap(iconbitmap, srcRect, destRect, null)
				// and finally the bitmap overlay, if any
				if (zoom >= 6 && symbolIsOverlayed(s.symbol)) {
					c.drawText(s.symbol(0).toString(), p.x, p.y+ss/2, symbStrPaint)
					c.drawText(s.symbol(0).toString(), p.x, p.y+ss/2, symbPaint)
				}
			}
		}
		}
	}

	def loadDb(showObjects : Boolean) {
		stations.clear()
		val filter = if (showObjects) null else "ORIGIN IS NULL"
		val c = db.getPositions(filter, null, null)
		c.moveToFirst()
		while (!c.isAfterLast()) {
			val call = c.getString(StorageDatabase.Position.COLUMN_CALL)
			val symbol = c.getString(StorageDatabase.Position.COLUMN_SYMBOL)
			val comment = c.getString(StorageDatabase.Position.COLUMN_COMMENT)
			val lat = c.getInt(StorageDatabase.Position.COLUMN_LAT)
			val lon = c.getInt(StorageDatabase.Position.COLUMN_LON)
			addStation(new Station(new GeoPoint(lat, lon), call, comment, symbol))
			c.moveToNext()
		}
		c.close()
		setLastFocusedIndex(-1)
		populate()
		Log.d(TAG, "total %d items".format(size()))
	}

	def addStation(sta : Station) {
		//if (calls.contains(sta.getTitle()))
		//	return
		//calls.add(sta.getTitle(), true)
		stations.add(sta)
	}

	override def onTap(index : Int) : Boolean = {
		val s = stations(index)
		Log.d(TAG, "user clicked on " + s.call)
		context.startActivity(new Intent(context, classOf[StationActivity]).putExtra("call", s.call));
		true
	}
}
