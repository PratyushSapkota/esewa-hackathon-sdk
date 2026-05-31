# Challenge 2: Real-Time KYC Document Quality Detection
# Table of Contents

1. [Team Name - Stealer Trojan](#1-team-name---stealer-trojan)
2. [Team Member Details](#2-team-member-details)
3. [Problem Understanding](#3-problem-understanding)
4. [Proposed Solution](#4-proposed-solution)
5. [In-Scope & Out-Scope](#5-in-scope--out-scope)
   - [In Scope](#in-scope)
   - [Out of Scope](#out-of-scope)
6. [User Journey/Flow](#6-user-journeyflow)
7. [Technology Stack](#7-technology-stack)
   - [7.1 Algorithms](#71-algorithms)
     - [The Laplacian of Gaussian (LoG)](#the-laplacian-of-gaussian-log)
       - [Formula – Gaussian Smoothing](#formula)
       - [Explanation](#explanation)
       - [How It Works](#how-it-works)
       - [Formula – Laplacian Operator](#formula-1)
       - [Explanation](#explanation-1)
       - [How It Works](#how-it-works-1)
       - [Common Discrete Kernels](#common-discrete-kernels)
         - [4-Neighbour Kernel](#4-neighbour-kernel)
         - [8-Neighbour Kernel](#8-neighbour-kernel)
       - [Applications](#applications)
     - [Mean Value of Luminance](#mean-value-of-luminance)
       - [Formula](#formula-2)
       - [Explanation](#explanation-2)
       - [How It Works](#how-it-works-2)
       - [For a Grayscale Image](#for-a-grayscale-image)
       - [Applications](#applications-1)
   - [7.2 OpenCV (Open Source Computer Vision Library)](#72-opencv-open-source-computer-vision-library)
   - [7.3 Android CameraX](#73-android-camerax)
   - [7.4 YOLO Model (You Only Look Once)](#74-yolo-model-you-only-look-once)
   - [7.5 ONNX (Open Neural Network Exchange)](#75-onnx-open-neural-network-exchange)
   - [7.6 Label Studio](#76-label-studio)
   - [7.7 SDK (Software Development Kit)](#77-sdk-software-development-kit)
8. [Architecture Diagram](#8-architecture-diagram)
9. [Wireframe or UI/UX Designs](#9-wireframe-or-uiux-designs)
10. [Limitations](#10-limitations)
    - [10.1 Resource Usage](#101-resource-usage)
    - [10.2 Model Accuracy (Hallucination Risk)](#102-model-accuracy-hallucination-risk)
    - [10.3 Hardcoded Thresholds](#103-hardcoded-thresholds)
    - [10.4 Overall System Load](#104-overall-system-load)
11. [Scalability and Future Enhancements](#11-scalability-and-future-enhancements)
    - [11.1 Larger and More Diverse Training Dataset](#111-larger-and-more-diverse-training-dataset)
    - [11.2 Efficient Deep Learning Models](#112-efficient-deep-learning-models)
    - [11.3 System Resource Optimization](#113-system-resource-optimization)
    - [11.4 iOS Implementation](#114-ios-implementation)

## 1. Team Name - Stealer Trojan
## 2. Team Member Details
| Full Name   | Email                     | Role                     | Permanent Address      | Gender |
|-------------|---------------------------|--------------------------|------------------------|--------|
| Aryan Sethi | sethiaryan217@gmail.com   | Technical Documentation  | Kathmandu   | Male   |
| Pratyush Sapkota | pratyushsapkota@gmail.com   |  System Developer  |              Kathmandu   | Male   |
| Rijan Bhattarai | rijanbhattarai2006@gmail.com   | AI Developer  | Kathmandu   | Male   |
| Roshan Yadav | roshanyadav1724@gmail.com   |  Designer  | Kathmandu   | Male   |

## 3. Problem Understanding

eSewa receives more than 5,000 **KYC (Know our Customer)** submission daily. However, around __40%__ of these submission are rejected despite user providing legitimate documents leading to repeated submission and increased support inquiries.

The primary factors contributing to the rejection are image quality issues, particularly:
- Blurred Images.
- Glare and poor lighting conditions.
- Incorrect document framing.

Among these, __blur__ and __incorrect framing__ contributes to approximately 30% of the total rejection.

Addressing these quality-related rejection during document validation in __real-time__ provides a significant opportunity to improve user experience, reduce verification delays, and optimize support operations.

## 4. Proposed Solution

By introducing a real-time edge verification system driven by __Algorithm__ and __Computer Vision__ we aim to reduce the number of rejected KYC submissions along with reduction in the server load resources and the human verifiers and an improved rate in user satisfaction.

Technologies used to achieve the real-time verifications we used:
- Native Android
- OpenCV _(Open Source Computer Vision Library)_

## 5. In-Scope & Out-Scope

### In Scope

The Document Analyzer SDK focuses exclusively on **real‑time document image quality assessment** during capture:

- **Edge detection** – locating the four corners of the document within the camera frame
- **Blur/sharpness evaluation** – ensuring the captured image is not blurry
- **Brightness analysis** – detecting underexposed (too dark) or overexposed frames
- **Glare detection** – identifying reflections and specular highlights
- **User guidance** – providing visual hints (alignment, lighting, glare) to help the user achieve a high‑quality capture
- **Auto‑capture** – triggering the snapshot when all quality metrics pass a configurable threshold

### Out of Scope

The SDK does **not** perform any verification or interpretation of the document’s content or legal status:

- **Document validity** – no forgery detection, authenticity checks, or tampering analysis
- **Ownership verification** – no identity matching, name‑ID cross‑check, or KYC processes
- **Cross‑platform support** – the SDK is designed and tested exclusively for **Android** _(Kotlin/Java with CameraX and OpenCV)_. There is no iOS, web, or desktop version, which provides cross‑platform wrappers _(e.g., Flutter, React Native, Xamarin)_ as part of this release.
- **Content analysis** – no extraction, classification, or understanding of document fields (e.g., dates, amounts, signatures) beyond the readability confidence score
- **Text readability** – measuring OCR confidence to confirm that text is legible
- **Data extraction** – the SDK returns quality scores and corner coordinates only; it does not parse or store personal data
- **Document Analysis** – the SDK was built to analyze real, physical documents placed under the camera despite that images displayed on a computer monitor, phone screen, or any other digital display are accepted. Photos of screens may introduce patterns, reflections, and low quality that fall outside the SDK’s scope and can lead to unreliable scores.

This ensures the SDK remains a real‑time quality‑control tool that can be integrated into broader document workflows without making decisions about the document’s meaning or trustworthiness.


## 6. User Journey/Flow
<figure align="center">
  <img src="https://github.com/AryanxSethi/Documentation/raw/main/UserFlow.jpg" alt="User Journey/Flow">
  <figcaption>User Journey/Flow</figcaption>
</figure>

1. Start Capture

    The user opens the app and begins scanning the document.

2. Real-Time Quality Checks

    As the user positions the document, the system continuously evaluates:
    - Blur
    - Lighting
    - Document Edges
    - Alignment and placement

3. Instant User Feedback

    Real-time guidance is provided to help the user improve image quality and positioning.

4. Automatic Capture

    When all quality checks meet the predefined threshold, the image is captured automatically.


## 7. Technology Stack
### 7.1 Algorithms:
## The Laplacian of Gaussian _(LoG)_

<dd>The Laplacian of Gaussian algorithm detects image blur by a second-order differential operator to measure the edge sharpness. It works on the principle that sharp, well-focused images contain rapid intensity changes <i>(high-frequency edges)</i>, resulting in a high variance when convolved with a Laplacian filter.</dd>

## Formula

- **Gaussian Smoothing:** The 2D Gaussian function is commonly used for **smoothing or blurring images**.

$$
G(x,y) = \frac{1}{2\pi\sigma^2} \, e^{-\frac{x^2 + y^2}{2\sigma^2}}
$$

## Explanation

- **\(x, y\)** – horizontal and vertical distances from the center of the kernel.  
- $(\sigma)$ – controls the “spread” of the Gaussian; larger $(\sigma)$ gives stronger smoothing.  
- $({1}/{2\pi\sigma^2})$ – normalizes the kernel so the total weight sums to 1 (brightness stays consistent).  
- $(e^{-\frac{x^2 + y^2}{2\sigma^2}})$ – assigns higher weights to points near the center, lower weights further away, producing a smooth, bell-shaped kernel.

### How It Works

Applying this kernel to an image replaces each pixel with a weighted average of its neighbors. Pixels closer to the center contribute more, **reducing noise and smoothing sharp edges**.

## Formula

- **Laplacian Operator**: The Laplacian operator is a **second-order derivative operator** used in image processing to detect regions of rapid intensity change, such as **edges** and fine details.

$$
\nabla^2 f(x,y) =
\frac{\partial^2 f}{\partial x^2}
+
\frac{\partial^2 f}{\partial y^2}
$$

## Explanation

- $f(x,y)$ – the image intensity at position \((x,y)\).
- $(\frac{\partial^2 f}{\partial x^2})$ – the second derivative in the horizontal direction.
- $(\frac{\partial^2 f}{\partial y^2})$ – the second derivative in the vertical direction.
- $(\nabla^2)$ – represents the Laplacian operator.

### How It Works

The Laplacian measures how quickly image intensity changes around a pixel by combining the second derivatives in both the horizontal and vertical directions. Areas with little intensity variation produce values close to zero, while regions containing edges or fine details produce large positive or negative values.

Because the Laplacian is highly sensitive to noise, it is often applied after a smoothing step, such as Gaussian filtering. This combination is known as the **Laplacian of Gaussian (LoG)** method.

## Common Discrete Kernels

### 4-Neighbour Kernel

$$
\begin{bmatrix}
0 & -1 & 0 \\
-1 & 4 & -1 \\
0 & -1 & 0
\end{bmatrix}
$$

### 8-Neighbour Kernel

$$
\begin{bmatrix}
-1 & -1 & -1 \\
-1 & 8 & -1 \\
-1 & -1 & -1
\end{bmatrix}
$$

### Applications

- Edge detection
- Image sharpening
- Feature extraction
- Blob detection
- Computer vision preprocessing

## Mean Value of Luminance

The mean value of luminance represents the **average brightness** of an image. It is commonly used in image processing to measure the overall intensity level and to compare the brightness of different images.

## Formula

$$
\mu = \frac{1}{N}\sum_{i=1}^{N} L_i
$$

## Explanation

- $(\mu)$ – the mean luminance of the image.
- $(N)$ – the total number of pixels in the image.
- $(L_i)$ – the luminance (brightness) value of the \(i\)-th pixel.
- $(\sum)$ – indicates that the luminance values of all pixels are added together.

### How It Works

The mean luminance is calculated by summing the luminance values of every pixel in the image and dividing by the total number of pixels. The result is a single value that represents the image's average brightness.

- A **higher mean luminance** indicates a brighter image.
- A **lower mean luminance** indicates a darker image.
- A value near the middle of the luminance range suggests a moderately illuminated image.

## For a Grayscale Image

If the image dimensions are $(M \times N)$, the mean luminance can also be expressed as:

$$
\mu = \frac{1}{MN}
\sum_{x=0}^{M-1}
\sum_{y=0}^{N-1}
L(x,y)
$$

where:

- **\(L(x,y)\)** is the luminance value at pixel location \((x,y)\).
- **\(M\)** and **\(N\)** are the image width and height.

### Applications

- Measuring overall image brightness
- Image quality assessment
- Exposure analysis
- Image normalization
- Preprocessing for computer vision algorithms

## 7.2 OpenCV (Open Source Computer Vision Library):
Computer vision engine was utilized for real-time document detection, edge analysis, blur estimation, brightness evaluation, and glare detection. All image-processing pipelines _(Canny edge, Laplacian variance, LAB color space analysis, morphological operations)_ run through OpenCV’s native library.

## 7.3 Android CameraX
Used for Android Camera integration and frame delivery. Provides a lifecycle‑aware, efficient pipeline that feeds YUV frames to the analyzer.

## 7.4 YOLO Model _(You Only Look Once)_

YOLO is a real‑time object detection model that can directly localize and classify objects in a single pass. For the document scanning, a lightweight YOLO variant was integrated to detect the bounding regions of the document providing a robust, fast and accurate solution.

## 7.5 ONNX _(Open Neural Network Exchange)_
Inference engine for running custom deep learning models _(e.g., YOLO for document corner detection)_ on‑device with hardware acceleration. This allows the SDK to optionally replace or augment the contour‑based detector with a more robust neural network.

## 7.6 Label Studio 
It is a web‑based tool for labelling and annotating document images. It was utilized to create training datasets _(bounding boxes, corner key points, blur/glare classifications)_ for custom detection models.

## 7.7 SDK (Software Development Kit)
Please click  [here](https://github.com/AryanxSethi/Documentation/blob/main/DEVELOPER_HANDOFF.md) for the **DocumentAnalyzer (SDK)** documentation.

## 8. Architecture Diagram

<figure align="center">
  <img src="https://github.com/AryanxSethi/Documentation/raw/main/SystemArch.jpg" alt="Architecture Diagram">
  <figcaption>Architecture Diagram</figcaption>
</figure>

## 9. Wireframe or UI/UX Designs:

<figure align="center">
  <img src="https://github.com/AryanxSethi/Documentation/raw/main/Landing.jpg"
       alt="Landing page"
       width="40%">
  <figcaption>Landing Page UI</figcaption>
</figure>
<figure align="center">
  <img src="https://github.com/AryanxSethi/Documentation/raw/main/camera.jpg"
       alt="Landing page"
       width="40%">
  <figcaption>Camera Page UI with real-time feedback</figcaption>
</figure>
<figure align="center">
  <img src="https://github.com/AryanxSethi/Documentation/raw/main/preview.jpg"
       alt="Landing page"
       width="40%">
  <figcaption>Preview Page UI</figcaption>
</figure>

## 10. Limitations

### 10.1 Resource Usage
The current implementation is not optimized for old low‑end devices. During real‑time analysis, the SDK can consume **up to 50% of the device’s available memory** and places a sustained load on the CPU/GPU. This may cause performance degradation, battery drain, or thermal throttling on devices with limited RAM or processing power. Future releases will focus on reducing the memory footprint and processing overhead.

### 10.2 Model Accuracy (Hallucination Risk)
When a deep‑learning model (e.g., YOLO via ONNX) is used for document detection, its predictions are only as reliable as the training data. With a limited or non‑diverse dataset, the model may **hallucinate** document corners, falsely detect documents in background objects, or miss documents with unfamiliar layouts, paper types, or lighting conditions. Accuracy should be validated in the target environment before production use.

### 10.3 Hardcoded Thresholds
Several quality‑assessment parameters are hardcoded in the detection functions:
- Blur variance threshold
- Brightness mean threshold
- Glare intensity and ratio thresholds

These values are not exposed as a unified configuration API, making it difficult to tune the SDK for different document types, camera sensors, or lighting conditions without modifying the source code.

### 10.4 Overall System Load
The SDK runs multiple computationally intensive pipelines _(OpenCV contour analysis, Laplacian blur estimation, ML Vision)_ concurrently on every frame. This makes the **application as a whole resource‑heavy**, potentially interfering with other camera‑related tasks or causing the device to heat up under prolonged use.

## 11. Scalability and Future Enhancements

The current version of the Document Analyzer SDK lays a strong foundation for real‑time KYC document quality detection. The following enhancements are planned to improve scalability, accuracy, and platform reach.

### 11.1 Larger and More Diverse Training Dataset
The detection model currently relies on a limited dataset. To reduce hallucination risks and improve accuracy across different document types, lighting conditions, and camera sensors, we plan to:

- Expand the dataset with **thousands of annotated images** covering various document categories (citizenship certificates, passports, driving licences, etc.).
- Include challenging scenarios: low‑light environments, extreme angles, partial occlusion, and different surface textures.
- Utilise **data augmentation** and synthetic generation to simulate realistic edge cases.

### 11.2 Efficient Deep Learning Models
To balance accuracy and on‑device performance, future iterations will adopt:

- **Lightweight architectures** (e.g., MobileNet, EfficientNet‑Lite, NanoDet) tailored for real‑time inference on mobile CPUs and GPUs.
- **Quantisation and pruning** to reduce model size and inference latency without significant accuracy loss.
- **Multi‑task learning** – a single model that simultaneously performs document detection, blur estimation, and glare classification, reducing the overall processing pipeline overhead.

### 11.3 System Resource Optimization
Current resource consumption (up to 50% memory usage) will be addressed through:

- **Frame skipping and adaptive processing rates** – dynamically adjusting analysis frequency based on device performance and scene stability.
- **Memory pooling and native buffer reuse** – minimizing garbage collection overhead and reducing Mat allocation/deallocation.
- **Partial pipeline execution** – disabling computationally expensive stages when not required, based on real‑time quality feedback.

### 11.4 iOS Implementation
The SDK is currently exclusive to Android. To serve a broader user base, we will develop an iOS counterpart that:

- Leverages **AVFoundation** for camera capture and frame delivery.
- Uses the same OpenCV and ONNX Runtime libraries, compiled for iOS (arm64).
- Maintains a unified API design across platforms, enabling seamless integration for cross‑platform applications.

