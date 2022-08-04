/***********************************************************************************************************************
*  Copyright 2021 kirkhansen
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*  Weatherstack Weather Driver
*
*  Author: kirkhansen
*
*  Date: 2021-12-28
*
*  attribution: weather data courtesy: http://weatherstack.com
*
*  attribution: sunrise and sunset courtesy: https://sunrise-sunset.org/
*
*  attribution: This is a modified version of what bangali has posted https://github.com/adey/bangali/blob/master/driver/apixu-weather.groovy
*
* for use with HUBITAT so no tiles
*
* features:
* - supports global weather data with free api key from weatherstack.com
* - provides calculated illuminance data based on time of day and weather weather code.
* - no local server setup needed
* - no personal weather station needed
*
***********************************************************************************************************************/

public static String version() {
    return 'v1.0.3'
}

/***********************************************************************************************************************
* Version: 1.0.3
*   08/03/2022: Add cron string for every 6 hours. Use http for sunrise-sunset to avoid https cert failures.
* Version: 1.0.2
*   12/28/2021: Fix cron strings for every hour and every 30 minutes
* Version: 1.0.1
*   07/09/2020: Fix broken estimateLux.
* Version: 1.0.0
*   04/15/2020: initial release.
*/

import groovy.transform.Field

@Field static final String STR = 'string'
@Field static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"

metadata  {
    definition (name: 'Weatherstack Weather Driver', namespace: 'kirkhansen', author: 'kirkhansen')  {
        capability 'Actuator'
        capability 'Sensor'
        capability 'Polling'
        capability 'Illuminance Measurement'
        capability 'Temperature Measurement'
        capability 'Relative Humidity Measurement'
        capability 'Pressure Measurement'
        capability 'Ultraviolet Index'

        attribute 'name', STR
        attribute 'region', STR
        attribute 'country', STR
        attribute 'lat', STR
        attribute 'lon', STR
        attribute 'timezone_id', STR
        attribute 'localtime_epoch', STR
        attribute 'local_time', STR
        attribute 'local_date', STR
        attribute 'observation_time', STR
        attribute 'utc_offset', STR

        attribute 'is_day', STR
        attribute 'weather_text', STR
        attribute 'weather_icon', STR
        attribute 'weather_code', STR
        attribute 'wind_speed', STR
        attribute 'wind_degree', STR
        attribute 'wind_dir', STR
        attribute 'precip', STR

        attribute 'cloudcover', STR
        attribute 'feelslike', STR
        attribute 'visibility', STR

        attribute 'location', STR
        attribute 'city', STR
        attribute 'local_sunrise', STR
        attribute 'local_sunset', STR
        attribute 'twilight_begin', STR
        attribute 'twilight_end', STR
        attribute 'illuminated', STR
        attribute 'cCF', STR
        attribute 'lastXUupdate', STR

        command 'refresh'
    }

    preferences  {
        input 'zipCode', 'text', title:'Zip code or city name or latitude,longitude?', required:true
        input 'weatherstackKey', 'password', title:'Weatherstack key?', required:true
        input 'cityName', 'text', title: 'Override default city name?', required:false, defaultValue:null
        input 'units', 'enum', title:'Select Units', required:true, defaultValue:5, options:['m':'Metric', 'f':'Fahrenheit', 's': 'Scientific']
        input 'dashClock', 'bool', title:'Flash time every 2 seconds?', required:true, defaultValue:false
        input(
            'pollEvery', 'enum',
            title:'Poll Weatherstack how frequently?',
            required:true,
            defaultValue:'0 0 */4 ? * * *',
            options:['0 */5 * ? * * *':'5 minutes',
                     '0 */10 * ? * * *':'10 minutes',
                     '0 */15 * ? * * *': '15 minutes',
                     '0 */30 * ? * * *':'30 minutes',
                     '0 0 * ? * * *':'1 Hour',
                     '0 0 */4 ? * * *':'4 hours',
                     '0 0 */6 ? * * *':'6 hours',
                    ]
        )
        input(
            'luxEvery', 'enum',
            title:'Publish illuminance how frequently?',
            required:true,
            defaultValue:'* */5 * ? * * *',
            options:['0 */5 * ? * * *':'5 minutes',
                     '0 */10 * ? * * *':'10 minutes',
                     '0 */15 * ? * * *': '15 minutes',
                     '0 */30 * ? * * *':'30 minutes',
                     '0 0 * ? * * *':'1 Hour',
                     '0 0 */4 ? * * *':'4 hours',
                     '0 0 */6 ? * * *':'6 hours',
                     ]
        )
        //logging message config
        input name: 'debugLogging', type: 'bool', title: 'Enable debug message logging', description: '', defaultValue: false
    }
}

@Field final Map WX_UNIT_LOOKUP = [
   'm': [
        'temperature': '°C',
        'speed': 'kph',
        'precipitation': 'mm',
        'pressure': 'mbar',
        'distance': 'km',
    ],
    'f': [
        'temperature': '°F',
        'speed': 'mph',
        'precipitation': 'in',
        'pressure': 'in',
        'distance': 'mi',
    ],
    's': [
        'temperature': '°K',
        'speed': 'kph',
        'precipitation': 'mm',
        'pressure': 'mbar',
        'distance': 'km',
    ]
]

void updated() {
    displayDebugLog('Updated called')

    unschedule()
    state.tz_id = null
    state.localDate = null
    state.clockSeconds = true
    poll()
    schedule(pollEvery, poll)
    schedule(luxEvery, updateLux)
    if (dashClock) {
        updateClock()
    }
}

void poll() {
    displayDebugLog("Executing 'poll', location: $zipCode")
    Map obs = getObservation()
    if (!obs)   {
        log.warn 'No response from weatherstack API'
        return
    }
    displayDebugLog("$obs")

    String dateTimeFormat = 'yyyy-MM-dd HH:mm'
    String hourMinFormat = 'HH:mm'

    TimeZone tZ = TimeZone.getTimeZone(obs.location.timezone_id)
    Date localTime = new Date().parse(dateTimeFormat, obs.location.localtime, tZ)
    String localDate = localTime.format('yyyy-MM-dd', tZ)
    String localTimeOnly = localTime.format(hourMinFormat, tZ)
    Map sunriseAndSunset = getSunriseAndSunset(obs.location.lat, obs.location.lon, localDate)
    sunriseTime = new Date().parse(DATE_TIME_FORMAT, sunriseAndSunset.results.sunrise, tZ)
    sunsetTime = new Date().parse(DATE_TIME_FORMAT, sunriseAndSunset.results.sunset, tZ)
    noonTime = new Date().parse(DATE_TIME_FORMAT, sunriseAndSunset.results.solar_noon, tZ)
    twilight_begin = new Date().parse(DATE_TIME_FORMAT, sunriseAndSunset.results.civil_twilight_begin, tZ)
    twilight_end = new Date().parse(DATE_TIME_FORMAT, sunriseAndSunset.results.civil_twilight_end, tZ)
    localSunrise = sunriseTime.format(hourMinFormat, tZ)
    localSunset = sunsetTime.format(hourMinFormat, tZ)
    tB = twilight_begin.format(hourMinFormat, tZ)
    tE = twilight_end.format(hourMinFormat, tZ)

    state.tz_id = obs.location.timezone_id
    state.sunriseTime = sunriseTime.format(DATE_TIME_FORMAT, tZ)
    state.sunsetTime = sunsetTime.format(DATE_TIME_FORMAT, tZ)
    state.noonTime = noonTime.format(DATE_TIME_FORMAT, tZ)
    state.twilight_begin = twilight_begin.format(DATE_TIME_FORMAT, tZ)
    state.twilight_end = twilight_end.format(DATE_TIME_FORMAT, tZ)
    state.weather_code = obs.current.weather_code
    state.cloudcover = obs.current.cloudcover

    updateLux()

    sendEvent(name: 'local_sunrise', value: localSunrise, descriptionText: "Sunrise today is at $localSunrise",
        displayed: true)
    sendEvent(name: 'local_sunset', value: localSunset, descriptionText: "Sunset today at is $localSunset",
        displayed: true)
    sendEvent(name: 'twilight_begin', value: tB, descriptionText: "Twilight begins today at $tB", displayed: true)
    sendEvent(name: 'twilight_end', value: tE, descriptionText: "Twilight ends today at $tE", displayed: true)
    sendEvent(name: 'name', value: obs.location.name, displayed: true)
    sendEvent(name: 'region', value: obs.location.region, displayed: true)
    sendEvent(name: 'country', value: obs.location.country, displayed: true)
    sendEvent(name: 'lat', value: obs.location.lat, displayed: true)
    sendEvent(name: 'lon', value: obs.location.lon, displayed: true)
    sendEvent(name: 'tz_id', value: obs.location.timezone_id, displayed: true)
    sendEvent(name: 'localtime_epoch', value: obs.location.localtime_epoch, displayed: true)
    sendEvent(name: 'local_time', value: localTimeOnly, displayed: true)
    sendEvent(name: 'local_date', value: localDate, displayed: true)
    sendEvent(name: 'observation_time', value: obs.current.observation_time, displayed: true)
    sendEvent(name: 'temperature', value: obs.current.temperature, unit: WX_UNIT_LOOKUP[units]['temperature'],
        displayed: true)
    sendEvent(name: 'is_day', value: obs.current.is_day, displayed: true)
    sendEvent(name: 'weather_description', value: obs.current.weather_descriptions[0], displayed: true)
    sendEvent(name: 'weather_icon', value: '<img src=' + obs.current.weather_icons[0] + '>', displayed: true)
    sendEvent(name: 'weather_code', value: obs.current.weather_code, displayed: true)
    sendEvent(name: 'wind_speed', value: obs.current.wind_speed, unit: WX_UNIT_LOOKUP[units]['speed'], displayed: true)
    sendEvent(name: 'wind_degree', value: obs.current.wind_degree, unit: 'degree', displayed: true)
    sendEvent(name: 'wind_dir', value: obs.current.wind_dir, displayed: true)
    sendEvent(name: 'pressure', value: obs.current.pressure, unit: WX_UNIT_LOOKUP[units]['pressure'], displayed: true)
    sendEvent(name: 'precipitation', value: obs.current.precip, unit: WX_UNIT_LOOKUP[units]['precipitation'],
        displayed: true)
    sendEvent(name: 'feelslike', value: obs.current.feelslike, unit: WX_UNIT_LOOKUP[units]['temperature'],
        displayed: true)
    sendEvent(name: 'visibility', value: obs.current.visibility, unit:WX_UNIT_LOOKUP[units]['distance'],
        displayed: true)
    sendEvent(name: 'humidity', value: obs.current.humidity, unit: '%', displayed: true)
    sendEvent(name: 'cloudcover', value: obs.current.cloudcover, unit: '%', displayed: true)
    sendEvent(name: 'location', value: obs.location.name + ', ' + obs.location.region, displayed: true)
    sendEvent(name: 'city', value: (cityName ?: obs.location.name), displayed: true)
    return
}

def refresh() { poll() }

def configure() { poll() }

private Map getObservation() {
    Map obs = [:]
    uri = "http://api.weatherstack.com/current?access_key=$weatherstackKey&query=$zipCode&units=$units"
    try {
        // TODO: add check for success
        httpGet(uri) { resp ->
            if (resp?.data) {
                displayDebugLog('getObservation returned data')
                obs = resp.data
            } else {
                log.error("weatherstack api did not return data: $resp")
            }
        }
    } catch (e) {
        log.error("http call failed for weatherstack weather api: $e")
    }
    return obs
}

private Map getSunriseAndSunset(latitude, longitude, forDate) {
    String uri = "http://api.sunrise-sunset.org/json?lat=$latitude&lng=$longitude&date=$forDate&formatted=0"
    Map sunriseAndSunset = [:]
    try {
        httpGet(uri) { resp ->
            if (resp?.data) {
                displayDebugLog('getSunriseAndSunset returned data')
                sunriseAndSunset = resp.data
            } else {
              log.error("api.sunrise-sunset.org did not return data: $resp")
            }
        }
    } catch (e) {
        log.error("http call failed for sunrise-sunset.org api: $e")
    }
    return sunriseAndSunset
}

def updateLux() {
    if (!state.sunriseTime
        || !state.sunsetTime
        || !state.noonTime
        || !state.twilight_begin
        || !state.twilight_end
        || !state.tz_id) {
        return
    }

    TimeZone tZ = TimeZone.getTimeZone(state.tz_id)
    String lT = new Date().format(DATE_TIME_FORMAT, tZ)
    Date localTime = new Date().parse(DATE_TIME_FORMAT, lT, tZ)
    Date sunriseTime = new Date().parse(DATE_TIME_FORMAT, state.sunriseTime, tZ)
    Date sunsetTime = new Date().parse(DATE_TIME_FORMAT, state.sunsetTime, tZ)
    Date noonTime = new Date().parse(DATE_TIME_FORMAT, state.noonTime, tZ)
    Date twilight_begin = new Date().parse(DATE_TIME_FORMAT, state.twilight_begin, tZ)
    Date twilight_end = new Date().parse(DATE_TIME_FORMAT, state.twilight_end, tZ)
    lux = estimateLux(localTime, sunriseTime, sunsetTime, noonTime, twilight_begin,
        twilight_end, state.weather_code, state.cloudcover, state.tz_id)
    sendEvent(name: 'illuminance', value: lux, unit: 'lux', displayed: true)
    sendEvent(name: 'illuminated', value: String.format('%,d lux', lux), displayed: true)
}

private estimateLux(localTime, sunriseTime, sunsetTime, noonTime, twilight_begin,
                    twilight_end, weather_code, cloudcover, tz_id) {
    displayDebugLog("weather_code: $weather_code | cloudcover: $cloudcover")
    displayDebugLog("twilight_begin: $twilight_begin | twilight_end: $twilight_end | tz_id: $tz_id")
    displayDebugLog("localTime: $localTime | sunriseTime: $sunriseTime | noonTime: $noonTime | sunsetTime: $sunsetTime")

    String tZ = TimeZone.getTimeZone(tz_id)
    long lux = 0L
    boolean aFCC = true
    long l

    if (timeOfDayIsBetween(sunriseTime, noonTime, localTime, tZ))      {
        displayDebugLog('between sunrise and noon')
        l = (((localTime.getTime() - sunriseTime.getTime()) * 10000f) / (noonTime.getTime() - sunriseTime.getTime()))
        lux = (l < 50f ? 50L : l as long)
    }
    else if (timeOfDayIsBetween(noonTime, sunsetTime, localTime, tZ))      {
        displayDebugLog('between noon and sunset')
        l = (((sunsetTime.getTime() - localTime.getTime()) * 10000f) / (sunsetTime.getTime() - noonTime.getTime()))
        lux = (l < 50f ? 50L : l as long)
    }
    else if (timeOfDayIsBetween(twilight_begin, sunriseTime, localTime, tZ))      {
        displayDebugLog('between sunrise and twilight')
        l = (((localTime.getTime() - twilight_begin.getTime()) * 50f) /
               (sunriseTime.getTime() - twilight_begin.getTime()))
        lux = (l < 10f ? 10L : l as long)
    }
    else if (timeOfDayIsBetween(sunsetTime, twilight_end, localTime, tZ))      {
        displayDebugLog('between sunset and twilight')
        l = (((twilight_end.getTime() - localTime.getTime()) * 50f) / (twilight_end.getTime() - sunsetTime.getTime()))
        lux = (l < 10f ? 10L : l as long)
    }
    else if (!timeOfDayIsBetween(twilight_begin, twilight_end, localTime, tZ))      {
        displayDebugLog('between non-twilight')
        lux = 5L
        aFCC = false
    }

    Integer cC = weather_code.toInteger()
    String cCT = ''
    Float cCF
    if (aFCC) {
        if (weatherFactor[cC])    {
            cCF = weatherFactor[cC][1]
            cCT = weatherFactor[cC][0]
        }
        else    {
            cCF = ((100 - (cloudcover.toInteger() / 3d)) / 100).round(1)
            cCT = 'using cloud cover'
        }
    }
    else    {
        cCF = 1.0
        cCT = 'night time now'
    }

    lux = (lux * cCF) as long
    displayDebugLog("weather: $cC | weather text: $cCT | weather factor: $cCF | lux: $lux")
    sendEvent(name: 'cCF', value: cCF, displayed: true)

    return lux
}

private timeOfDayIsBetween(fromDate, toDate, checkDate, timeZone) {
    return (!checkDate.before(fromDate) && !checkDate.after(toDate))
}

def updateClock() {
    runIn(2, updateClock)
    if (!state.tz_id || ! tz_id) {
        return
    }
    Date nowTime = new Date()
    String tZ = TimeZone.getTimeZone(state.tz_id)
    sendEvent(name: 'local_time', value: nowTime.format((state.clockSeconds ? 'HH:mm' : 'HH mm'), tZ), displayed: true)
    String localDate = nowTime.format('yyyy-MM-dd', tZ)
    if (localDate != state.localDate) {
        state.localDate = localDate
        sendEvent(name: 'local_date', value: localDate, displayed: true)
    }
    state.clockSeconds = (state.clockSeconds ? false : true)
}

@Field final Map weatherFactor = [
    1000: ['Sunny', 1, 'sunny'],                                        1003: ['Partly cloudy', 0.8, 'partlycloudy'],
    1006: ['Cloudy', 0.6, 'cloudy'],                                    1009: ['Overcast', 0.5, 'cloudy'],
    1030: ['Mist', 0.5, 'fog'],                                         1063: ['Patchy rain possible', 0.8, 'chancerain'],
    1066: ['Patchy snow possible', 0.6, 'chancesnow'],                  1069: ['Patchy sleet possible', 0.6, 'chancesleet'],
    1072: ['Patchy freezing drizzle possible', 0.4, 'chancesleet'],     1087: ['Thundery outbreaks possible', 0.2, 'chancetstorms'],
    1114: ['Blowing snow', 0.3, 'snow'],                                1117: ['Blizzard', 0.1, 'snow'],
    1135: ['Fog', 0.2, 'fog'],                                          1147: ['Freezing fog', 0.1, 'fog'],
    1150: ['Patchy light drizzle', 0.8, 'rain'],                        1153: ['Light drizzle', 0.7, 'rain'],
    1168: ['Freezing drizzle', 0.5, 'sleet'],                           1171: ['Heavy freezing drizzle', 0.2, 'sleet'],
    1180: ['Patchy light rain', 0.8, 'rain'],                           1183: ['Light rain', 0.7, 'rain'],
    1186: ['Moderate rain at times', 0.5, 'rain'],                      1189: ['Moderate rain', 0.4, 'rain'],
    1192: ['Heavy rain at times', 0.3, 'rain'],                         1195: ['Heavy rain', 0.2, 'rain'],
    1198: ['Light freezing rain', 0.7, 'sleet'],                        1201: ['Moderate or heavy freezing rain', 0.3, 'sleet'],
    1204: ['Light sleet', 0.5, 'sleet'],                                1207: ['Moderate or heavy sleet', 0.3, 'sleet'],
    1210: ['Patchy light snow', 0.8, 'flurries'],                       1213: ['Light snow', 0.7, 'snow'],
    1216: ['Patchy moderate snow', 0.6, 'snow'],                        1219: ['Moderate snow', 0.5, 'snow'],
    1222: ['Patchy heavy snow', 0.4, 'snow'],                           1225: ['Heavy snow', 0.3, 'snow'],
    1237: ['Ice pellets', 0.5, 'sleet'],                                1240: ['Light rain shower', 0.8, 'rain'],
    1243: ['Moderate or heavy rain shower', 0.3, 'rain'],               1246: ['Torrential rain shower', 0.1, 'rain'],
    1249: ['Light sleet showers', 0.7, 'sleet'],                        1252: ['Moderate or heavy sleet showers', 0.5, 'sleet'],
    1255: ['Light snow showers', 0.7, 'snow'],                          1258: ['Moderate or heavy snow showers', 0.5, 'snow'],
    1261: ['Light showers of ice pellets', 0.7, 'sleet'],               1264: ['Moderate or heavy showers of ice pellets', 0.3, 'sleet'],
    1273: ['Patchy light rain with thunder', 0.5, 'tstorms'],           1276: ['Moderate or heavy rain with thunder', 0.3, 'tstorms'],
    1279: ['Patchy light snow with thunder', 0.5, 'tstorms'],           1282: ['Moderate or heavy snow with thunder', 0.3, 'tstorms']
    ]

private void displayDebugLog(String message) {
    if (debugLogging) {
        log.debug("${device.displayName}: ${message}")
    }
}
