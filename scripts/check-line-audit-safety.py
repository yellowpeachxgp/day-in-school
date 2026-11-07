#!/usr/bin/env python3
"""Offline source invariants; not a substitute for Kotlin/Android tests."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def source(name): return next(root.rglob(name)).read_text()
auth=source('HautLibrarySeatAuthClient.kt')
booking=auth.split('suspend fun executeAutomatedSeatBooking(',1)[1].split('private fun executeAuthenticatedApiGet',1)[0]
assert booking.count('/seatbook/user/addbooking?') == 1
assert 'allowAlternativeSeat: Boolean = false' in booking
assert 'record.bookingId == id && record.seatId == seat.id' in booking
assert 'record.startDate == startFull && record.endDate == endFull' in booking
assert 'matchedHistorical' not in booking
cancel=auth.split('if (action == LibrarySeatActionType.CANCEL_BOOKING',1)[1].split('private fun inferLayerNameFromRegion',1)[0]
assert '/signin?' not in cancel and '/quickSignIn?' not in cancel
repo=source('CampusRepository.kt')
assert 'private val bookingMutationMutex = Mutex()' in repo
stage=repo.split('suspend fun advanceBookingPlanStage',1)[1].split('suspend fun cancelDualEngineBookingPlan',1)[0]
assert stage.count('executeAutomatedSeatBooking(')==1
assert stage.index('status = "RESULT_UNKNOWN"') < stage.index('executeAutomatedSeatBooking(')
assert 'status = "REMINDER_DELIVERED"' in stage
assert 'utilityMutationMutex.withLock { runCatching {' in repo
assert 'awaitSessionOperations()' in source('AccountSessionCoordinator.kt')
assert 'EXTRA_GENERATION' in source('LibrarySeatAlarmSubsystem.kt')
assert 'only the repository' in source('HautAcademicProvider.kt')
assert 'course.source != source' in source('ImportDiffEngine.kt')
assert 'start >= 1 && end <= 60' in source('TeachingWeekCalculator.kt')
assert 'estimatedLength > MAX_NUMBER_LENGTH' in source('CanonicalJson.kt')
assert 'consent == null || !consent.covers(required)' in source('PackageInstaller.kt')
assert 'val (lat, lng) = second to first' in source('AcademicAndCampusModels.kt')
assert 'priceMicropeach = syncedPrice' in repo
assert 'paymentState = syncedPayment' in repo
assert 'targetId.all(Char::isDigit)' in repo
assert 'val resolvedId = if (bookingId != null)' in auth
assert 'automaticWritesEnabled: Boolean = false' in source('SchoolWriteAuthorization.kt')
assert 'SchoolWriteAuthorization.requireVerified()' in booking
assert 'localItem.copy(status = "LOCAL_DRAFT")' in repo
assert 'val localPlanUpserts = emptyList<' in repo
assert 'cloudBaseUrl = baseUrl' in repo.split('suspend fun advanceBookingPlanStage',1)[1]
print('PASS: offline source safety invariants (not Kotlin compilation)')
