package com.dsg.asmcosttime

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.dsg.annotations.CostTime

class MainActivity : AppCompatActivity() {


    @CostTime
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    @CostTime
    fun getVaildData(): Int {
        return 111
    }
}

