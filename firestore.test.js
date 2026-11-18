const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-a10bda36-8b0b-4d37-bbad-aba175fc020a";
const ALICE_UID = "alice_driver_1";
const BOB_UID = "bob_driver_2";
const ADMIN_UID = "admin_salem_1";
const ADMIN_EMAIL = "salemjalaal1@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function serverTs() {
  return new Date(Date.now() - 2000);
}

function dbFor(context) {
  return context.firestore({ databaseId: DATABASE_ID });
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write customers, trips, trucks, or settings", async () => {
  const unauthDb = dbFor(testEnv.unauthenticatedContext());
  await assertFails(unauthDb.collection("customers").get());
  await assertFails(unauthDb.collection("trips").get());
  await assertFails(unauthDb.collection("trucks").get());
  await assertFails(unauthDb.collection("settings").doc("main").get());
  await assertFails(unauthDb.collection("customers").doc("c1").set({ id: "c1", name: "Anon" }));
});

test("Unverified email token: denied even if claiming admin email", async () => {
  const spoofAdminDb = dbFor(
    testEnv.authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
  );
  await assertFails(spoofAdminDb.collection("customers").get());
  await assertFails(spoofAdminDb.collection("trucks").get());
});

test("Authenticated user: cannot query without ownerId filter or read another user's customer", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = dbFor(context);
    await adminDb.collection("customers").doc("bob_cust").set({
      id: "bob_cust",
      ownerId: BOB_UID,
      name: "Bob Customer",
      phone: "777000111",
      district: "العرقين",
      lat: 15.48,
      lng: 45.36,
      tankCapacityLiters: 2000,
      createdAt: "2026-11-15T10:00:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    });
  });

  const aliceDb = dbFor(
    testEnv.authenticatedContext(ALICE_UID, {
      email: "alice@example.com",
      email_verified: true,
    })
  );

  // Unfiltered collection read fails
  await assertFails(aliceDb.collection("customers").get());
  // Cross-user get fails
  await assertFails(aliceDb.collection("customers").doc("bob_cust").get());
});

test("Authenticated user: CRUD on own customer & trip succeeds with valid audit fields", async () => {
  const aliceDb = dbFor(
    testEnv.authenticatedContext(ALICE_UID, {
      email: "alice@example.com",
      email_verified: true,
    })
  );

  await assertSucceeds(
    aliceDb.collection("customers").doc("cust_alice_1").set({
      id: "cust_alice_1",
      ownerId: ALICE_UID,
      name: "شركة مأرب للمقاولات",
      phone: "771234567",
      district: "الصمدة",
      lat: 15.49,
      lng: 45.37,
      tankCapacityLiters: 4000,
      createdAt: "2026-11-15T12:00:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("customers").where("ownerId", "==", ALICE_UID).get()
  );

  await assertSucceeds(
    aliceDb.collection("customers").doc("cust_alice_1").update({
      name: "شركة مأرب المحدثة",
      serverUpdatedAt: serverTs(),
    })
  );

  // Reject spoofing ownerId on create
  await assertFails(
    aliceDb.collection("customers").doc("cust_spoof").set({
      id: "cust_spoof",
      ownerId: BOB_UID,
      name: "Spoof",
      phone: "770000000",
      district: "العرقين",
      lat: 15.48,
      lng: 45.36,
      tankCapacityLiters: 2000,
      createdAt: "2026-11-15T12:00:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
});

test("Bootstrapped admin (verified email): can list fleet trucks and trips across owners", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminBypass = dbFor(context);
    await adminBypass.collection("trucks").doc("TRUCK-02").set({
      id: "TRUCK-02",
      ownerId: BOB_UID,
      code: "TRUCK-02",
      plateNumber: "مأرب 2040",
      maxCapacityLiters: 12000,
      status: "on_trip",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    });
  });

  const adminDb = dbFor(
    testEnv.authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
  );

  await assertSucceeds(adminDb.collection("trucks").get());
  await assertSucceeds(
    adminDb.collection("settings").doc("main").set({
      id: "main",
      ownerId: ADMIN_UID,
      waterProductionCostPerLiter: 0.4,
      defaultSellingPricePerLiter: 1.5,
      dieselPricePerLiter: 1200,
      gpsPowerSaveEnabled: true,
      gpsPowerSaveIntervalSeconds: 60,
      gpsStationaryDwellSeconds: 60,
      plantLocation: {
        name: "مصنع مياهنا",
        city: "مأرب - الوادي",
        lat: 15.4855,
        lng: 45.362,
      },
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    adminDb.collection("settings").doc("main").update({
      gpsPowerSaveIntervalSeconds: "not-a-number",
      serverUpdatedAt: serverTs(),
    })
  );
});

test("tripStateLogs: enforces auth, owner scoping, immutable audit trail, and manager-only review", async () => {
  const unauthDb = dbFor(testEnv.unauthenticatedContext());
  await assertFails(unauthDb.collection("tripStateLogs").get());
  await assertFails(
    unauthDb.collection("tripStateLogs").doc("log_unauth").set({
      id: "log_unauth",
      ownerId: ALICE_UID,
      tripId: "trip_1",
      fromState: "approved",
      toState: "accepted",
      triggerSource: "driver_accept",
      actorRole: "driver",
      actorName: "سالم",
      timestamp: "2026-11-16T12:00:00Z",
      timestampEpochMs: Date.now(),
      reviewedByManager: false,
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  const aliceDb = dbFor(
    testEnv.authenticatedContext(ALICE_UID, {
      email: "alice@example.com",
      email_verified: true,
    })
  );
  const bobDb = dbFor(
    testEnv.authenticatedContext(BOB_UID, {
      email: "bob@example.com",
      email_verified: true,
    })
  );
  const adminDb = dbFor(
    testEnv.authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
  );

  // Non-admin cannot create with reviewedByManager: true
  await assertFails(
    aliceDb.collection("tripStateLogs").doc("log_spoof_rev").set({
      id: "log_spoof_rev",
      ownerId: ALICE_UID,
      tripId: "trip_10",
      fromState: "approved",
      toState: "accepted",
      triggerSource: "driver_accept",
      actorRole: "driver",
      actorName: "سالم",
      timestamp: "2026-11-16T12:00:00Z",
      timestampEpochMs: Date.now(),
      reviewedByManager: true,
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  // Reject invalid state enum
  await assertFails(
    aliceDb.collection("tripStateLogs").doc("log_bad_state").set({
      id: "log_bad_state",
      ownerId: ALICE_UID,
      tripId: "trip_10",
      fromState: "approved",
      toState: "hacked_state",
      triggerSource: "driver_accept",
      actorRole: "driver",
      actorName: "سالم",
      timestamp: "2026-11-16T12:00:00Z",
      timestampEpochMs: Date.now(),
      reviewedByManager: false,
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("tripStateLogs").doc("log_ghost").set({
      id: "log_ghost",
      ownerId: ALICE_UID,
      tripId: "trip_10",
      fromState: "approved",
      toState: "accepted",
      triggerSource: "driver_accept",
      actorRole: "driver",
      actorName: "سالم",
      timestamp: "2026-11-16T12:00:00Z",
      timestampEpochMs: Date.now(),
      reviewedByManager: false,
      extraTamperField: "nope",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  // Valid owner creates across accepted -> in_progress -> paused -> completed_pending_review
  await assertSucceeds(
    aliceDb.collection("tripStateLogs").doc("log_alice_1").set({
      id: "log_alice_1",
      ownerId: ALICE_UID,
      tripId: "trip_10",
      tripNumber: "TRIP-2026-0010",
      truckId: "truck-1",
      driverId: "drv-1",
      driverName: "سالم جلال",
      fromState: "in_progress",
      toState: "paused",
      triggerSource: "auto_stop_paused",
      actorRole: "system",
      actorName: "التتبع التلقائي",
      reason: "توقف ميداني لأكثر من 3 دقائق",
      lat: 15.495,
      lng: 45.368,
      speedKmH: 0.0,
      timestamp: "2026-11-16T12:05:00Z",
      timestampEpochMs: Date.now(),
      reviewedByManager: false,
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  // Owner can query own logs, Bob cannot read Alice's log or list unfiltered
  await assertSucceeds(
    aliceDb.collection("tripStateLogs").where("ownerId", "==", ALICE_UID).get()
  );
  await assertFails(bobDb.collection("tripStateLogs").doc("log_alice_1").get());
  await assertFails(bobDb.collection("tripStateLogs").get());

  // Owner cannot mutate historical toState or review status or delete the log
  await assertFails(
    aliceDb.collection("tripStateLogs").doc("log_alice_1").update({
      toState: "closed",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    aliceDb.collection("tripStateLogs").doc("log_alice_1").update({
      reviewedByManager: true,
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(aliceDb.collection("tripStateLogs").doc("log_alice_1").delete());

  // Bootstrapped admin can list across all owners and mark log reviewed
  await assertSucceeds(adminDb.collection("tripStateLogs").get());
  await assertSucceeds(
    adminDb.collection("tripStateLogs").doc("log_alice_1").update({
      reviewedByManager: true,
      reviewedAt: "2026-11-16T12:15:00Z",
      reviewNote: "تم التحقق من سبب التوقف",
      serverUpdatedAt: serverTs(),
    })
  );
});

test("Two-phone shared database: authorized driver (mixed-case email) reads & updates allowed ops fields; denied admin/financial tampering; stranger denied", async () => {
  const DRIVER_UID = "driver_phone_2";
  const DRIVER_EMAIL_MIXED = "DriverUser@gmail.com";
  const DRIVER_EMAIL_LOWER = "driveruser@gmail.com";
  const MEMBER_EMAILS = [ADMIN_EMAIL, DRIVER_EMAIL_LOWER];

  const adminDb = dbFor(
    testEnv.authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
  );
  const driverDb = dbFor(
    testEnv.authenticatedContext(DRIVER_UID, {
      email: DRIVER_EMAIL_MIXED,
      email_verified: true,
    })
  );
  const strangerDb = dbFor(
    testEnv.authenticatedContext(BOB_UID, {
      email: "bob@example.com",
      email_verified: true,
    })
  );

  // Manager Phone 1 seeds shared workspace documents with memberEmails
  await assertSucceeds(
    adminDb.collection("settings").doc("main").set({
      id: "main",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      waterProductionCostPerLiter: 0.4,
      defaultSellingPricePerLiter: 1.5,
      dieselPricePerLiter: 1200,
      plantLocation: { name: "مصنع مياهنا", city: "مأرب - الوادي", lat: 15.4855, lng: 45.362 },
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("trucks").doc("TRUCK-01").set({
      id: "TRUCK-01",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      code: "TRUCK-01",
      plateNumber: "مأرب 1001",
      maxCapacityLiters: 6000,
      status: "idle",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("driverAccounts").doc("drv_shared_1").set({
      id: "drv_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      name: "أحمد السائق",
      username: "ahmed",
      phone: "777111222",
      password: "pass1234",
      googleEmail: DRIVER_EMAIL_LOWER,
      status: "approved",
      createdAt: "2026-11-18T08:00:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("customers").doc("cust_shared_1").set({
      id: "cust_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      name: "عميل مشترك",
      phone: "777333444",
      district: "العرقين",
      lat: 15.48,
      lng: 45.36,
      tankCapacityLiters: 2000,
      createdAt: "2026-11-18T08:00:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("orders").doc("ord_shared_1").set({
      id: "ord_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      customerName: "عميل مشترك",
      phone: "777333444",
      district: "العرقين",
      requestedLiters: 2000,
      status: "assigned",
      createdAt: "2026-11-18T08:05:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("trips").doc("trip_shared_1").set({
      id: "trip_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      tripNumber: "TRIP-2026-0100",
      truckId: "TRUCK-01",
      driverId: "drv_shared_1",
      driverName: "أحمد السائق",
      status: "approved",
      driverAcceptanceStatus: "pending",
      customerStops: [],
      orderIds: ["ord_shared_1"],
      proposedAt: "2026-11-18T08:10:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("directives").doc("dir_shared_1").set({
      id: "dir_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      createdAt: "2026-11-18T08:11:00Z",
      createdBy: "المدير العام",
      title: "توجه للعميل",
      message: "التوصيل صباحاً",
      status: "sent",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("notifications").doc("notif_shared_1").set({
      id: "notif_shared_1",
      ownerId: ADMIN_UID,
      memberEmails: MEMBER_EMAILS,
      createdAt: "2026-11-18T08:11:00Z",
      targetRole: "driver",
      title: "رحلة جديدة",
      message: "تم إسناد رحلة جديدة",
      isRead: false,
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    adminDb.collection("invoices").doc("inv_private_1").set({
      id: "inv_private_1",
      ownerId: ADMIN_UID,
      invoiceNumber: "INV-0100",
      tripId: "trip_shared_1",
      customerName: "عميل مشترك",
      totalAmount: 3000,
      createdAt: "2026-11-18T08:12:00Z",
      serverCreatedAt: serverTs(),
      serverUpdatedAt: serverTs(),
    })
  );

  // Driver Phone 2 queries shared collections via array-contains & reads settings/main
  await assertSucceeds(driverDb.collection("settings").doc("main").get());
  await assertSucceeds(driverDb.collection("settings").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("trucks").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("driverAccounts").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("trips").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("orders").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("directives").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("notifications").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertSucceeds(driverDb.collection("customers").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());

  // Driver Phone 2 can perform allowed operational updates
  await assertSucceeds(
    driverDb.collection("trucks").doc("TRUCK-01").update({
      status: "on_trip",
      lastLat: 15.491,
      lastLng: 45.366,
      lastSpeed: 38.5,
      lastUpdateTimestamp: "2026-11-18T08:20:00Z",
      currentTripId: "trip_shared_1",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("trips").doc("trip_shared_1").update({
      driverAcceptanceStatus: "accepted",
      driverRespondedAt: "2026-11-18T08:15:00Z",
      status: "in_progress",
      startedAt: "2026-11-18T08:16:00Z",
      gpsPoints: [{ id: "pt1", tripId: "trip_shared_1", lat: 15.491, lng: 45.366, speedKmH: 38.5, timestamp: "2026-11-18T08:20:00Z", seq: 1 }],
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("orders").doc("ord_shared_1").update({
      status: "in_delivery",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("directives").doc("dir_shared_1").update({
      status: "acknowledged",
      acknowledgedAt: "2026-11-18T08:17:00Z",
      driverReply: "تم الاستلام",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("notifications").doc("notif_shared_1").update({
      isRead: true,
      serverUpdatedAt: serverTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("driverAccounts").doc("drv_shared_1").update({
      lastLoginAt: "2026-11-18T08:14:00Z",
      serverUpdatedAt: serverTs(),
    })
  );

  // Driver Phone 2 is DENIED mutating Manager settings, customers, invoices, or protected fields
  await assertFails(
    driverDb.collection("settings").doc("main").update({
      defaultSellingPricePerLiter: 99,
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    driverDb.collection("customers").doc("cust_shared_1").update({
      name: "Hacked Name",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(driverDb.collection("invoices").doc("inv_private_1").get());
  await assertFails(
    driverDb.collection("trips").doc("trip_shared_1").update({
      approvedBy: "Fake Admin",
      closedAt: "2026-11-18T09:00:00Z",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    driverDb.collection("trips").doc("trip_shared_1").update({
      memberEmails: [DRIVER_EMAIL_LOWER],
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    driverDb.collection("trips").doc("trip_shared_1").update({
      ownerId: DRIVER_UID,
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    driverDb.collection("driverAccounts").doc("drv_shared_1").update({
      status: "rejected",
      serverUpdatedAt: serverTs(),
    })
  );
  await assertFails(
    driverDb.collection("notifications").doc("notif_shared_1").update({
      title: "Tampered Title",
      serverUpdatedAt: serverTs(),
    })
  );

  // Unauthorized stranger (bob@example.com) querying own email sees 0 docs; cannot read/query/update Manager/Driver docs
  await assertFails(strangerDb.collection("settings").doc("main").get());
  await assertFails(strangerDb.collection("trips").doc("trip_shared_1").get());
  const bobEmptySnap = await assertSucceeds(
    strangerDb.collection("trips").where("memberEmails", "array-contains", "bob@example.com").get()
  );
  if (bobEmptySnap.size !== 0) {
    throw new Error(`Expected 0 docs for stranger, got ${bobEmptySnap.size}`);
  }
  await assertFails(strangerDb.collection("trips").where("memberEmails", "array-contains", DRIVER_EMAIL_LOWER).get());
  await assertFails(strangerDb.collection("trips").get());
  await assertFails(
    strangerDb.collection("trips").doc("trip_shared_1").update({
      status: "paused",
      serverUpdatedAt: serverTs(),
    })
  );
});


