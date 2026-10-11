package com.revela.app

import android.app.Application
import com.revela.app.data.PhotoRepository
import com.revela.app.tether.TetherManager

class RevelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhotoRepository.init(this)
        TetherManager.init(this)
    }
}
