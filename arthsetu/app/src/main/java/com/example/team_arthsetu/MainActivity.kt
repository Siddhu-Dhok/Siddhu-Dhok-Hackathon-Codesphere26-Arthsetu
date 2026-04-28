package com.example.team_arthsetu

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.example.team_arthsetu.fcm.FcmTokenSync
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.ui.BankStatementActivity
import com.example.team_arthsetu.ui.GoalsListActivity
import com.example.team_arthsetu.ui.ProfileSetupActivity
import com.example.team_arthsetu.ui.SetExpenseLimitActivity
import com.example.team_arthsetu.ui.StatementCloudActivity
import com.example.team_arthsetu.utils.NotificationHelper
import com.example.team_arthsetu.utils.ProfilePhotoUtils
import com.example.team_arthsetu.utils.ProfileStore
import com.example.team_arthsetu.utils.InitialSmsSyncManager
import com.example.team_arthsetu.utils.TransactionNotificationHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var drawerLayout: DrawerLayout
    private lateinit var navController: NavController

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val hideNavDestinations = setOf(
        R.id.loginFragment,
        R.id.otpFragment,
        R.id.savedSimulationsFragment,
        R.id.savedSimulationDetailFragment
    )

    private val hideAppBarDestinations = setOf(
        R.id.loginFragment,
        R.id.otpFragment,
        R.id.expenseFragment,
        R.id.wealthFragment,
        R.id.simulationFragment,
        R.id.goalsFragment,
        R.id.dashboardFragment,
        R.id.savedSimulationsFragment,
        R.id.savedSimulationDetailFragment
    )

    private lateinit var appBarConfig: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createChannel(this)
        TransactionNotificationHelper.ensureChannel(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            InitialSmsSyncManager.performInitialSmsSync(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        drawerLayout  = findViewById(R.id.drawerLayout)
        val toolbar   = findViewById<MaterialToolbar>(R.id.toolbar)
        val appBar    = findViewById<AppBarLayout>(R.id.appBarLayout)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val navView   = findViewById<NavigationView>(R.id.navView)

        val navHost   = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        val topLevel = setOf(
            R.id.dashboardFragment, R.id.expenseFragment,
            R.id.wealthFragment, R.id.goalsFragment, R.id.simulationFragment
        )
        appBarConfig = AppBarConfiguration(topLevel, drawerLayout)
        setSupportActionBar(toolbar)
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig)
        bottomNav.setupWithNavController(navController)
        // Keep multi-color vector icons (do not tint everything to primary).
        bottomNav.itemIconTintList = null

        navController.addOnDestinationChangedListener { _, dest, _ ->
            appBar.visibility =
                if (dest.id in hideAppBarDestinations) View.GONE else View.VISIBLE
            bottomNav.visibility =
                if (dest.id in hideNavDestinations) View.GONE else View.VISIBLE
            drawerLayout.setDrawerLockMode(
                if (dest.id in hideNavDestinations)
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                else
                    DrawerLayout.LOCK_MODE_UNLOCKED
            )
        }

        setupDrawer(navView)

        // Push local expense limits to Firestore so backend can evaluate new SMS txns
        FirebaseAuth.getInstance().currentUser?.let {
            FcmTokenSync.requestTokenAndSyncToFirestore(this)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    FirestoreRepository().syncExpenseLimitsFromLocal(this@MainActivity)
                    FirestoreRepository().syncCategoryLimitsFromLocal(this@MainActivity)
                } catch (_: Exception) { }
            }
        }
    }
    //  Public helpers


    fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)

    //  Drawer setup

    private fun setupDrawer(navView: NavigationView) {

        // ── Status-bar fix via WindowInsetsCompat ─────────────────────────────
        // This listener fires as soon as insets are available (after first layout)
        // and gives us the EXACT status bar height on every device/orientation.
        val headerSection = navView.findViewById<LinearLayout>(R.id.navHeaderSection)
        ViewCompat.setOnApplyWindowInsetsListener(navView) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            headerSection?.setPadding(
                dpToPx(20),
                statusBarTop + dpToPx(12),
                dpToPx(20),
                dpToPx(20)
            )
            insets
        }

        // ── Click listeners on custom menu items ──────────────────────────────
        navView.findViewById<View>(R.id.navItemBankStatement)?.setOnClickListener {
            closeAndLaunch(BankStatementActivity::class.java)
        }
        navView.findViewById<View>(R.id.navItemStatementCloud)?.setOnClickListener {
            closeAndLaunch(StatementCloudActivity::class.java)
        }
        navView.findViewById<View>(R.id.navItemMyGoals)?.setOnClickListener {
            closeAndLaunch(GoalsListActivity::class.java)
        }
        navView.findViewById<View>(R.id.navItemSavedSimulations)?.setOnClickListener {
            navController.navigate(R.id.savedSimulationsFragment)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        navView.findViewById<View>(R.id.navItemExpenseLimit)?.setOnClickListener {
            closeAndLaunch(SetExpenseLimitActivity::class.java)
        }
        navView.findViewById<View>(R.id.navItemProfile)?.setOnClickListener {
            closeAndLaunch(ProfileSetupActivity::class.java)
        }
        navView.findViewById<View>(R.id.navItemLogout)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            logoutUser()
        }

        // ── Refresh data every time the drawer opens ──────────────────────────
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = refreshNavHeader(navView)
        })
        refreshNavHeader(navView)
    }

    private fun <T : Any> closeAndLaunch(cls: Class<T>) {
        drawerLayout.closeDrawer(GravityCompat.START)
        startActivity(Intent(this, cls))
    }

    // ── Nav header data refresh ───────────────────────────────────────────────

    private fun refreshNavHeader(navView: NavigationView) {
        val tvName     = navView.findViewById<TextView>(R.id.tvNavName)     ?: return
        val tvPhone    = navView.findViewById<TextView>(R.id.tvNavPhone)    ?: return
        val ivPhoto    = navView.findViewById<ImageView>(R.id.ivNavPhoto)   ?: return
        val tvInitials = navView.findViewById<TextView>(R.id.tvNavInitials) ?: return

        // ── Profile ───────────────────────────────────────────────────────────
        val name      = ProfileStore.getName(this)
        val phone     = ProfileStore.getPhone(this)
            .ifBlank { FirebaseAuth.getInstance().currentUser?.phoneNumber ?: "" }
        val photoPath = ProfileStore.getPhotoPath(this)

        tvName.text  = name.ifBlank { "Complete Your Profile" }
        tvPhone.text = phone

        val bmp = if (photoPath.isNotBlank()) ProfilePhotoUtils.loadOrientedBitmap(photoPath) else null
        if (bmp != null) {
            ivPhoto.setImageBitmap(bmp)
            ivPhoto.visibility    = View.VISIBLE
            tvInitials.visibility = View.GONE
        } else {
            ivPhoto.visibility    = View.GONE
            tvInitials.visibility = View.VISIBLE
            tvInitials.text = name.trim().split("\\s+".toRegex())
                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                .take(2).joinToString("").ifBlank { "?" }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── Logout ────────────────────────────────────────────────────────────────

    private fun logoutUser() {
        FirebaseAuth.getInstance().signOut()
        ProfileStore.clear(this)
        navController.navigate(R.id.loginFragment)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean =
        NavigationUI.navigateUp(navController, appBarConfig) || super.onSupportNavigateUp()
}
