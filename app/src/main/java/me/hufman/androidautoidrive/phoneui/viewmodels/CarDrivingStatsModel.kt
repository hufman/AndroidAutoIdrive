package me.hufman.androidautoidrive.phoneui.viewmodels

import me.hufman.androidautoidrive.carapp.CDSVehicleUnits
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsDouble
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonObject
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonPrimitive
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsString

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.carapp.liveData
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.addUnit
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.combine
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.format
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.map
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsInt
import me.hufman.idriveconnectionkit.CDS
import java.lang.Exception
import java.text.DateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

class CarDrivingStatsModel(carInfoOverride: CarInformation? = null, val showAdvancedSettings: BooleanLiveSetting): ViewModel() {
	companion object {
		val CACHED_KEYS = setOf(
				CDS.VEHICLE.VIN,
				CDS.VEHICLE.UNITS,
				CDS.DRIVING.ODOMETER,

				CDS.DRIVING.AVERAGECONSUMPTION,
				CDS.DRIVING.AVERAGESPEED,
				CDS.DRIVING.DISPLAYRANGEELECTRICVEHICLE,        // doesn't need unit conversion
				CDS.DRIVING.DRIVINGSTYLE,
				CDS.DRIVING.ECORANGEWON,
				CDS.ENGINE.RANGECALC,
				CDS.NAVIGATION.GPSPOSITION,
				CDS.NAVIGATION.CURRENTPOSITIONDETAILEDINFO,
				CDS.SENSORS.BATTERY,
				CDS.SENSORS.FUEL,
				CDS.SENSORS.SOCBATTERYHYBRID,
				CDS.VEHICLE.TIME,
				CDS.ENGINE.TEMPERATURE,
				CDS.CONTROLS.SUNROOF,
				CDS.DRIVING.MODE,
				CDS.DRIVING.PARKINGBRAKE,
		)
	}

	class Factory(val appContext: Context): ViewModelProvider.Factory {
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			val handler = Handler()
			var model: CarDrivingStatsModel? = null
			val carInfo = CarInformationObserver {
				handler.post { model?.update() }
			}
			model = CarDrivingStatsModel(carInfo, BooleanLiveSetting(appContext, AppSettings.KEYS.SHOW_ADVANCED_SETTINGS))
			model.update()
			return model as T
		}
	}

	private val carInfo = carInfoOverride ?: CarInformation()

	private val _idriveVersion = MutableLiveData<String>(null)
	val idriveVersion: LiveData<String> = _idriveVersion

	// unit conversions
	val units: LiveData<CDSVehicleUnits> = carInfo.cachedCdsData.liveData[CDS.VEHICLE.UNITS].map(CDSVehicleUnits.UNKNOWN) {
		CDSVehicleUnits.fromCdsProperty(it)
	}

	val unitsAverageConsumptionLabel: LiveData<Context.() -> String> = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGECONSUMPTION].map({getString(R.string.lbl_carinfo_units_L100km)}) {
		val unit = CDSVehicleUnits.Consumption.fromValue(it.tryAsJsonObject("averageConsumption")?.getAsJsonPrimitive("unit")?.tryAsInt)
		when(unit) {
			CDSVehicleUnits.Consumption.MPG_UK -> {{ getString(R.string.lbl_carinfo_units_mpg) }}
			CDSVehicleUnits.Consumption.MPG_US -> {{ getString(R.string.lbl_carinfo_units_mpg) }}
			CDSVehicleUnits.Consumption.KM_L -> {{ getString(R.string.lbl_carinfo_units_kmL) }}
			CDSVehicleUnits.Consumption.L_100km -> {{ getString(R.string.lbl_carinfo_units_L100km) }}
		}
	}

	val unitsAverageSpeedLabel: LiveData<Context.() -> String> = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGESPEED].map({getString(R.string.lbl_carinfo_units_L100km)}) {
		val unit = CDSVehicleUnits.Speed.fromValue(it.tryAsJsonObject("averageSpeed")?.getAsJsonPrimitive("unit")?.tryAsInt)
		when(unit) {
			CDSVehicleUnits.Speed.KMPH -> {{ getString(R.string.lbl_carinfo_units_kmph) }}
			CDSVehicleUnits.Speed.MPH -> {{ getString(R.string.lbl_carinfo_units_mph) }}
		}
	}

	val unitsTemperatureLabel: LiveData<Context.() -> String> = units.map({getString(R.string.lbl_carinfo_units_celcius)}) {
		when (it.temperatureUnits) {
			CDSVehicleUnits.Temperature.CELCIUS -> {{ getString(R.string.lbl_carinfo_units_celcius) }}
			CDSVehicleUnits.Temperature.FAHRENHEIT -> {{ getString(R.string.lbl_carinfo_units_fahrenheit) }}
		}
	}

	val unitsDistanceLabel: LiveData<Context.() -> String> = units.map({getString(R.string.lbl_carinfo_units_km)}) {
		when (it.distanceUnits) {
			CDSVehicleUnits.Distance.Kilometers -> {{ getString(R.string.lbl_carinfo_units_km) }}
			CDSVehicleUnits.Distance.Miles -> {{ getString(R.string.lbl_carinfo_units_mi) }}
		}
	}

	val unitsFuelLabel: LiveData<Context.() -> String> = units.map({getString(R.string.lbl_carinfo_units_liter)}) {
		when (it.fuelUnits) {
			CDSVehicleUnits.Fuel.Liters -> {{ getString(R.string.lbl_carinfo_units_liter) }}
			CDSVehicleUnits.Fuel.Gallons_UK -> {{ getString(R.string.lbl_carinfo_units_gal_uk) }}
			CDSVehicleUnits.Fuel.Gallons_US -> {{ getString(R.string.lbl_carinfo_units_gal_us) }}
		}
	}

	// the visible LiveData objects
	val vin = carInfo.cachedCdsData.liveData[CDS.VEHICLE.VIN].map {
		it.tryAsJsonPrimitive("VIN")?.tryAsString
	}

	val hasConnected = vin.map(false) { true }

	val odometer = carInfo.cachedCdsData.liveData[CDS.DRIVING.ODOMETER].map {
		it.tryAsJsonPrimitive("odometer")?.tryAsDouble
	}.combine(units) { value, units ->
		units.distanceUnits.fromCarUnit(value)
	}.format("%.0f").addUnit(unitsDistanceLabel)

	val lastUpdate = carInfo.cachedCdsData.liveData[CDS.VEHICLE.TIME].map {
		try {
			val carTime = it.getAsJsonObject("time")
			val dateTime = GregorianCalendar(
				carTime.getAsJsonPrimitive("year").asInt,
				carTime.getAsJsonPrimitive("month").asInt - 1,
				carTime.getAsJsonPrimitive("date").asInt,
				carTime.getAsJsonPrimitive("hour").asInt,
				carTime.getAsJsonPrimitive("minute").asInt,
				carTime.getAsJsonPrimitive("second").asInt,
			).time
			DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(dateTime)
		} catch (e: Exception) { null }
	}

	val positionName = carInfo.cachedCdsData.liveData[CDS.NAVIGATION.CURRENTPOSITIONDETAILEDINFO].map {
		val num = it.tryAsJsonObject("currentPositionDetailedInfo")?.tryAsJsonPrimitive("houseNumber")?.tryAsString ?: ""
		val street = it.tryAsJsonObject("currentPositionDetailedInfo")?.tryAsJsonPrimitive("street")?.tryAsString ?: ""
		val crossStreet = it.tryAsJsonObject("currentPositionDetailedInfo")?.tryAsJsonPrimitive("crossStreet")?.tryAsString ?: ""
		val city = it.tryAsJsonObject("currentPositionDetailedInfo")?.tryAsJsonPrimitive("city")?.tryAsString ?: ""
		if (num.isNotBlank() && street.isNotBlank() && city.isNotBlank()) {
			"$num $street, $city"
		} else if (street.isNotBlank() && crossStreet.isNotBlank() && city.isNotBlank()) {
			"$street & $crossStreet, $city"
		} else if (street.isNotBlank() && crossStreet.isNotBlank() && city.isBlank()) {
			"$street & $crossStreet"
		} else if (num.isBlank() && street.isNotBlank() && city.isNotBlank()) {
			"$street, $city"
		} else {
			city
		}
	}
	val positionGeoName = carInfo.cachedCdsData.liveData[CDS.NAVIGATION.GPSPOSITION].map {
		val lat = it.tryAsJsonObject("GPSPosition")?.tryAsJsonPrimitive("latitude")?.tryAsDouble
		val long = it.tryAsJsonObject("GPSPosition")?.tryAsJsonPrimitive("longitude")?.tryAsDouble
		if (lat != null && long != null) {
			"$lat,$long"
		} else {
			null
		}
	}
	val positionGeoUri = carInfo.cachedCdsData.liveData[CDS.NAVIGATION.GPSPOSITION].combine(positionName) { it, name ->
		val lat = it.tryAsJsonObject("GPSPosition")?.tryAsJsonPrimitive("latitude")?.tryAsDouble
		val long = it.tryAsJsonObject("GPSPosition")?.tryAsJsonPrimitive("longitude")?.tryAsDouble
		if (lat != null && long != null) {
			val quotedName = Uri.encode(name)
			Uri.parse("geo:$lat,$long?q=$lat,$long($quotedName)")
		} else {
			null
		}
	}

	val evLevel = carInfo.cachedCdsData.liveData[CDS.SENSORS.SOCBATTERYHYBRID].map {
		it.tryAsJsonPrimitive("SOCBatteryHybrid")?.tryAsDouble?.takeIf { it < 255 }
	}
	val evLevelLabel = evLevel.format("%.1f %%")

	val fuelLevel = carInfo.cachedCdsData.liveData[CDS.SENSORS.FUEL].map {
		it.tryAsJsonObject("fuel")?.tryAsJsonPrimitive("tanklevel")?.tryAsDouble?.takeIf { it > 0 }
	}.combine(units) { value, units ->
		units.fuelUnits.fromCarUnit(value)
	}
	val fuelLevelLabel = fuelLevel.format("%.1f").addUnit(unitsFuelLabel)

	val accBatteryLevel = carInfo.cachedCdsData.liveData[CDS.SENSORS.BATTERY].map {
		it.tryAsJsonPrimitive("battery")?.tryAsDouble?.takeIf { it < 255 }
	}
	val accBatteryLevelLabel = accBatteryLevel.format("%.0f %%")

	val evRange = carInfo.cachedCdsData.liveData[CDS.DRIVING.DISPLAYRANGEELECTRICVEHICLE].map {
		it.tryAsJsonPrimitive("displayRangeElectricVehicle")?.tryAsDouble?.takeIf { it < 4095 }
	}
	val evRangeLabel = evRange.format("%.0f").addUnit(unitsDistanceLabel)

	// a non-nullable evRange for calculating the gas-only fuelRange
	private val _evRange = carInfo.cachedCdsData.liveData[CDS.DRIVING.DISPLAYRANGEELECTRICVEHICLE].map(0.0) {
		it.tryAsJsonPrimitive("displayRangeElectricVehicle")?.tryAsDouble?.takeIf { it < 4095 } ?: 0.0
	}

	val fuelRange = carInfo.cachedCdsData.liveData[CDS.SENSORS.FUEL].map {
		it.tryAsJsonObject("fuel")?.tryAsJsonPrimitive("range")?.tryAsDouble
	}.combine(units) { value, units ->
		units.distanceUnits.fromCarUnit(value)
	}.combine(_evRange) { totalRange, evRange ->
		max(0.0, totalRange - evRange)
	}
	val fuelRangeLabel = fuelRange.format("%.0f").addUnit(unitsDistanceLabel)

	val totalRange = carInfo.cachedCdsData.liveData[CDS.SENSORS.FUEL].map {
		it.tryAsJsonObject("fuel")?.tryAsJsonPrimitive("range")?.tryAsDouble
	}.combine(units) { value, units ->
		units.distanceUnits.fromCarUnit(value)
	}
	val totalRangeLabel = totalRange.format("%.0f").addUnit(unitsDistanceLabel)

	val averageConsumption = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGECONSUMPTION].map {
		it.tryAsJsonObject("averageConsumption")?.tryAsJsonPrimitive("averageConsumption1")?.tryAsDouble
	}.format("%.1f").addUnit(unitsAverageConsumptionLabel)

	val averageSpeed = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGESPEED].map {
		it.tryAsJsonObject("averageSpeed")?.tryAsJsonPrimitive("averageSpeed1")?.tryAsDouble
	}.format("%.1f").addUnit(unitsAverageSpeedLabel)

	val averageConsumption2 = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGECONSUMPTION].map {
		it.tryAsJsonObject("averageConsumption")?.tryAsJsonPrimitive("averageConsumption2")?.tryAsDouble?.takeIf { it < 2093 }
	}.format("%.1f").addUnit(unitsAverageConsumptionLabel)

	val averageSpeed2 = carInfo.cachedCdsData.liveData[CDS.DRIVING.AVERAGESPEED].map {
		it.tryAsJsonObject("averageSpeed")?.tryAsJsonPrimitive("averageSpeed2")?.tryAsDouble?.takeIf { it < 2093 }
	}.format("%.1f").addUnit(unitsAverageSpeedLabel)

	val drivingStyleAccel = carInfo.cachedCdsData.liveData[CDS.DRIVING.DRIVINGSTYLE].map {
		it.tryAsJsonObject("drivingStyle")?.tryAsJsonPrimitive("accelerate")?.tryAsInt
	}
	val drivingStyleBrake = carInfo.cachedCdsData.liveData[CDS.DRIVING.DRIVINGSTYLE].map {
		it.tryAsJsonObject("drivingStyle")?.tryAsJsonPrimitive("brake")?.tryAsInt
	}
	val drivingStyleShift = carInfo.cachedCdsData.liveData[CDS.DRIVING.DRIVINGSTYLE].map {
		it.tryAsJsonObject("drivingStyle")?.tryAsJsonPrimitive("shift")?.tryAsInt
	}

	val ecoRangeWon = carInfo.cachedCdsData.liveData[CDS.DRIVING.ECORANGEWON].map {
		it.tryAsJsonPrimitive("ecoRangeWon")?.tryAsDouble
	}.combine(units) { value, units ->
		units.distanceUnits.fromCarUnit(value)
	}.format("%.1f").addUnit(unitsDistanceLabel)

	fun update() {
		// any other updates
		_idriveVersion.value = carInfo.capabilities["hmi.version"]
	}

	/* JEZIKK additions */
	val engineTemp = carInfo.cachedCdsData.liveData[CDS.ENGINE.TEMPERATURE].map {
		it.tryAsJsonObject("temperature")?.tryAsJsonPrimitive("engine")?.tryAsDouble
	}.format("%.0f").addUnit(unitsTemperatureLabel)
	val oilTemp = carInfo.cachedCdsData.liveData[CDS.ENGINE.TEMPERATURE].map {
		it.tryAsJsonObject("temperature")?.tryAsJsonPrimitive("oil")?.tryAsDouble
	}.format("%.0f").addUnit(unitsTemperatureLabel)
	val speedActual = carInfo.cdsData.liveData[CDS.DRIVING.SPEEDACTUAL].map {
		it.tryAsJsonPrimitive("speedActual")?.tryAsDouble
	}.format("%.0f").addUnit(unitsAverageSpeedLabel)
	val speedDisplayed = carInfo.cdsData.liveData[CDS.DRIVING.SPEEDDISPLAYED].map {
		it.tryAsJsonPrimitive("speedDisplayed")?.tryAsDouble
	}.format("%.0f").addUnit(unitsAverageSpeedLabel)
	val tempInterior = carInfo.cdsData.liveData[CDS.SENSORS.TEMPERATUREINTERIOR].map {
		it.tryAsJsonPrimitive("temperatureInterior")?.tryAsDouble
	}.format("%.1f").addUnit(unitsTemperatureLabel)
	val tempExterior = carInfo.cdsData.liveData[CDS.SENSORS.TEMPERATUREEXTERIOR].map {
		it.tryAsJsonPrimitive("temperatureExterior")?.tryAsDouble
	}.format("%.1f").addUnit(unitsTemperatureLabel)

	//val drivingMode = carInfo.cdsData.liveData[CDS.DRIVING.MODE].map {
	val drivingMode = carInfo.cachedCdsData.liveData[CDS.DRIVING.MODE].map {
		val a = it.tryAsJsonObject("mode")?.tryAsJsonPrimitive("mode")?.tryAsInt
		val b = it.tryAsJsonPrimitive("mode")?.tryAsInt
		"DEBUG: $a - $b"
	}

	val parkingBrake = carInfo.cachedCdsData.liveData[CDS.DRIVING.PARKINGBRAKE].map {
		it.tryAsJsonPrimitive("parkingBrake")?.tryAsInt?.takeIf { it > 0 } ?: 255
	}


	val sunRoof =carInfo.cachedCdsData.liveData[CDS.CONTROLS.SUNROOF].map {
		val status = it.tryAsJsonObject("sunroof")?.tryAsJsonPrimitive("status")?.tryAsString?.takeIf { it !="" } ?: " "
		val openPosition = it.tryAsJsonObject("sunroof")?.tryAsJsonPrimitive("openPosition")?.tryAsString?.takeIf { it !="" } ?: " "
		val tiltPosition = it.tryAsJsonObject("sunroof")?.tryAsJsonPrimitive("tiltPosition")?.tryAsString?.takeIf { it !="" } ?: " "
		"DEBUG: Status: $status | Open: $openPosition | Tilt: $tiltPosition"
	}


	val drivingGear = carInfo.cdsData.liveData[CDS.DRIVING.GEAR].map {
		var gear = it.tryAsJsonPrimitive("gear")?.tryAsInt?.takeIf { it >0 } ?: 255
			if (gear == 1) {
				"R"
			}
			else if(gear == 2 ) {
				"N"
			}
			else if(gear == 3) {
				"P"
			}
			else if(gear >= 5 ) {
				gear -= 4
				"D $gear"
			}
			else {
				"-"
			}
	}

	val altitude = carInfo.cdsData.liveData[CDS.NAVIGATION.GPSEXTENDEDINFO].map {
		it.tryAsJsonObject("GPSExtendedInfo")?.tryAsJsonPrimitive("altitude")?.tryAsInt
	}

	val headingGPS = carInfo.cdsData.liveData[CDS.NAVIGATION.GPSEXTENDEDINFO].map {
		var heading = it.tryAsJsonObject("GPSExtendedInfo")?.tryAsJsonPrimitive("heading")?.tryAsDouble
		var direction = ""
		if (heading != null) {
			// heading defined in CCW manner, so we ned to inver to CW neutral direction weel.
			heading *= -1
			heading += 360
			//heading = -100 + 360  = 260;

			if((heading>=0 && heading<22.5) || (heading>=347.5 && heading<=359.99) ) {
				direction = "N"
			}
			else if (heading >= 22.5 && heading < 67.5) {
				direction = "NNE"
			}
			else if (heading>=67.5 && heading<112.5) {
				direction = "E"
			}
			else if (heading>=112.5 && heading < 157.5) {
				direction = "SE"
			}
			else if (heading>=157.5 && heading<202.5) {
				direction = "S"
			}
			else if (heading>=202.5 && heading<247.5) {
				direction = "SW"
			}
			else if (heading>=247.5 && heading<302.5){
				direction = "W"
			}
			else if (heading>=302.5 && heading<347.5) {
				direction = "S"
			}
			else {
				direction = "-"
			}

		}
		"$direction - $heading"
	}

}