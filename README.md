# WeatherStack Device Driver

## Summary
Virtual Device Driver for Hubitat. It will create a device that reads in lots of weather data from [weather stack's api](https://weatherstack.com/), gets sunrise and sunset times from [sunrise sunset](https://sunrise-sunset.org/).

This was largely ported from [DarkSky.net-WeatherDriver](https://github.com/HubitatCommunity/DarkSky.net-Weather-Driver)

## Installation
1. Copy over the `weatherstack.groovy` file into Hubitat as a Device Driver.
2. Create a new virtual device using the new `Weatherstack Weather Driver`
3. Create an account at weatherstack, and create a new API key.
4. Put API key in the `Weatherstack key` field.
5. Profit

## Note
With the free version, stick with the default of polling every hour as this should keep you under the allowed number of API calls.
