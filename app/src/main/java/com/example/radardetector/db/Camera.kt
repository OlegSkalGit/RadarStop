package com.example.radardetector.db

data class Camera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val isLinear: Boolean = false
)
