package com.noirandroid.testapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.noirandroid.lib.Noir
import com.noirandroid.lib.Circuit
import java.math.BigInteger
import android.util.Log
class MainActivity : AppCompatActivity() {
    private val testCircuitJson = """{"noir_version":"1.0.0-beta.19+11c71e40895a37aacb7501db45ece4890dc062cc","hash":"3046983709444744681","abi":{"parameters":[{"name":"a","type":{"kind":"field"},"visibility":"private"},{"name":"b","type":{"kind":"field"},"visibility":"private"},{"name":"result","type":{"kind":"field"},"visibility":"public"}],"return_type":null,"error_types":{}},"bytecode":"H4sIAAAAAAAA/4XMPQ5AMBiA4aqLGNmIE4hITGIUiUHCYPCTshh7g37tYDWYHEDYXaSb0WLXE/DObx6dw7TUedVgoKvX9yUZ0pK0AsRpoO80pAE/DbuIiHRma4+DjdIkM90rHI8OfPmIW234F0JcSZgx9gL3WmBNjQAAAA==","debug_symbols":"lZDBCoMwDIbfJeceZGMOfJUxpNYohZCW2A6G+O6Lsm562GGnNPn7/SH/DD12eWw9D2GC5jZDJ57Ijy0FZ5MPrNN5MVDaNgmijmCnKxWtICdoOBMZeFjK26cpWt5qsqJqZQC516qGgydcX4v50tVvtC5sffnAl/Pf9PVI37WzzsvhWqigOS2rmXjbEb4TGDK7XSDpGYtSIosSHPZZcLXbNF3wAg==","file_map":{"53":{"source":"fn main(a: Field, b: Field, result: pub Field) {\n    assert(a * b == result);\n}\n\n#[test]\nfn test_main() {\n    main(2, 5, 10);\n}\n"}}}""".trimIndent()
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val statusTextView = findViewById<TextView>(R.id.status_text)
        
        try {
            val circuit = Circuit.fromJsonManifest(testCircuitJson, 4096)
            val witness = circuit.execute(mapOf("a" to "0x2", "b" to "0x3", "result" to "0x6"))
            val a = witness[0].last()
            val b = witness[1].last()
            val result = witness[2].last()
            var result_str = "Circuit Execution Result:\n"
            result_str += "a: $a\n"
            result_str += "b: $b\n"
            result_str += "result: $result\n"
            circuit.setupSrs()
            val vkey = circuit.getVerificationKey()
            val proof = circuit.prove(mapOf("a" to "0x2", "b" to "0x3", "result" to "0x6"), vkey, "ultra_honk")
            // Truncate the proof to 100 characters
            val truncatedProof = proof.substring(0, Math.min(proof.length, 100))
            result_str += "Proof: $truncatedProof\n"
            // Truncate the verification key to 100 characters
            val truncatedVkey = vkey.substring(0, Math.min(vkey.length, 100))
            result_str += "Verification Key: $truncatedVkey\n"
            val verified = circuit.verify(proof, vkey, "ultra_honk")
            result_str += "Verified: $verified\n"
            statusTextView.text = result_str
        } catch (e: Exception) {
            statusTextView.text = "JNI Test Failed: ${e.message}"
            e.printStackTrace()
        }
    }
} 