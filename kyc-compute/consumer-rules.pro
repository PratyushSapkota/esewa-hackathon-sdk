-keep class com.example.kyc_compute.library.KycCompute { public *; }
-keep class com.example.kyc_compute.library.KycResult { public *; }
-keep class com.example.kyc_compute.library.KycConfig { public *; }
-keep class com.example.kyc_compute.library.Feedback { public *; }
-keep class com.example.kyc_compute.library.DetectedBox { public *; }

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.opencv.**
