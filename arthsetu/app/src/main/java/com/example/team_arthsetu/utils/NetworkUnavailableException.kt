package com.example.team_arthsetu.utils

/** Thrown when a Firestore write is skipped or cannot proceed due to no connectivity. */
class NetworkUnavailableException(message: String = "No network connectivity") : Exception(message)
