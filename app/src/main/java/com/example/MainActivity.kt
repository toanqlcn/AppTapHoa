package com.example

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.api.GeminiClient
import com.example.api.LookupMatchResult
import com.example.api.SuggestionResult
import com.example.data.AppDatabase
import com.example.data.Product
import com.example.data.ProductRepository
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.util.*

enum class CameraMode {
    NONE, ADD_PRODUCT, LOOKUP_PRODUCT
}

class MainViewModel(private val repository: ProductRepository) : ViewModel() {
    private val databaseProducts = repository.allProducts

    val searchQuery = MutableStateFlow("")

    val filteredProducts: StateFlow<List<Product>> = combine(
        databaseProducts,
        searchQuery
    ) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRawProducts = databaseProducts

    val cameraMode = MutableStateFlow(CameraMode.NONE)
    val isAnalyzing = MutableStateFlow(false)
    val analyzedProductMatch = MutableStateFlow<LookupMatchResult?>(null)
    
    val capturedImageFile = MutableStateFlow<File?>(null)
    
    // Add product form states
    val addingProductName = MutableStateFlow("")
    val addingProductPrice = MutableStateFlow("")
    val addingProductDescription = MutableStateFlow("")

    fun setCameraMode(mode: CameraMode) {
        cameraMode.value = mode
        if (mode == CameraMode.NONE) {
            capturedImageFile.value = null
            analyzedProductMatch.value = null
            addingProductName.value = ""
            addingProductPrice.value = ""
            addingProductDescription.value = ""
        }
    }

    fun onPhotoCaptured(file: File) {
        capturedImageFile.value = file
        val mode = cameraMode.value
        if (mode == CameraMode.ADD_PRODUCT) {
            getAiSuggestionForNewProduct(file)
        } else if (mode == CameraMode.LOOKUP_PRODUCT) {
            performProductLookup(file)
        }
    }

    private fun getAiSuggestionForNewProduct(file: File) {
        viewModelScope.launch {
            isAnalyzing.value = true
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val suggestion = GeminiClient.getProductSuggestion(bitmap)
                    if (suggestion != null) {
                        addingProductName.value = suggestion.name
                        addingProductPrice.value = suggestion.price.toString()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error getting AI suggestion", e)
            } finally {
                isAnalyzing.value = false
            }
        }
    }

    private fun performProductLookup(file: File) {
        viewModelScope.launch {
            isAnalyzing.value = true
            try {
                val currentProducts = databaseProducts.first()
                val productListText = if (currentProducts.isEmpty()) {
                    "(Không có sản phẩm nào trong cửa hàng. Hãy thêm sản phẩm trước)"
                } else {
                    currentProducts.joinToString("\n") { "[id: ${it.id}] ${it.name} - Giá VND: ${it.price}" }
                }

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val matchResult = GeminiClient.lookupProductPrice(bitmap, productListText)
                    if (matchResult != null) {
                        analyzedProductMatch.value = matchResult
                    } else {
                        analyzedProductMatch.value = LookupMatchResult(
                            found = false,
                            reason = "Mẫu AI không trả về dữ liệu chuẩn. Hãy chắc chắn đã có sản phẩm trong kho."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error performing price lookup", e)
                analyzedProductMatch.value = LookupMatchResult(
                    found = false,
                    reason = "Đã xảy ra lỗi khi quét: ${e.message}"
                )
            } finally {
                isAnalyzing.value = false
            }
        }
    }

    fun saveProduct(context: android.content.Context, imageFile: File, onComplete: () -> Unit) {
        viewModelScope.launch {
            val name = addingProductName.value.ifBlank { "Sản phẩm mới" }
            val price = addingProductPrice.value.toLongOrNull() ?: 0L
            val desc = addingProductDescription.value

            val permanentFile = File(context.filesDir, "product_${System.currentTimeMillis()}.jpg")
            try {
                imageFile.copyTo(permanentFile, overwrite = true)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to preserve photo", e)
            }

            val product = Product(
                name = name,
                price = price,
                imagePath = permanentFile.absolutePath,
                description = desc
            )
            repository.insert(product)
            setCameraMode(CameraMode.NONE)
            onComplete()
        }
    }

    fun saveSuggestedProduct(context: android.content.Context, suggestedName: String, suggestedPrice: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            val imageFile = capturedImageFile.value ?: return@launch
            val permanentFile = File(context.filesDir, "product_${System.currentTimeMillis()}.jpg")
            try {
                imageFile.copyTo(permanentFile, overwrite = true)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to copy suggestion photo", e)
            }

            val product = Product(
                name = suggestedName,
                price = suggestedPrice,
                imagePath = permanentFile.absolutePath,
                description = "Tự động thêm bằng tra cứu giá AI"
            )
            repository.insert(product)
            setCameraMode(CameraMode.NONE)
            onComplete()
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            repository.delete(product)
            try {
                val file = File(product.imagePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error removing photo file", e)
            }
        }
    }
}

class MainViewModelFactory(private val repository: ProductRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ProductRepository(database.productDao)

        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = MainViewModelFactory(repository)
                )
                MainAppScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val capturedImageFile by viewModel.capturedImageFile.collectAsStateWithLifecycle()
    val analyzedProductMatch by viewModel.analyzedProductMatch.collectAsStateWithLifecycle()
    val rawProducts by viewModel.allRawProducts.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (cameraMode == CameraMode.NONE) {
                DashboardScreen(viewModel)
            } else {
                if (cameraPermissionState.status.isGranted) {
                    CameraScanningScreen(
                        viewModel = viewModel,
                        cameraMode = viewModel.cameraMode,
                        isAnalyzing = viewModel.isAnalyzing,
                        capturedImageFile = viewModel.capturedImageFile,
                        analyzedProductMatch = viewModel.analyzedProductMatch,
                        rawProducts = rawProducts
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF9F9F9))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VideocamOff,
                                    contentDescription = "No Camera",
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Yêu cầu quyền Camera",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E2E2E),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Để quét trực tiếp sản phẩm và lưu hình ảnh, vui lòng cấp quyền truy cập Camera của điện thoại.",
                                    fontSize = 14.sp,
                                    color = Color(0xFF6E6E6E),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { cameraPermissionState.launchPermissionRequest() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cấp Quyền Ngay", color = Color.White)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { viewModel.setCameraMode(CameraMode.NONE) }
                                ) {
                                    Text("Quay Lại Trang Chủ", color = Color(0xFF6E6E6E))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val listProducts by viewModel.filteredProducts.collectAsStateWithLifecycle()
    val allProductsState by viewModel.allRawProducts.collectAsStateWithLifecycle(emptyList())

    var productToDelete by remember { mutableStateOf<Product?>(null) }

    // Colors & Theme Config
    val orangePrimary = Color(0xFFE65100)
    val lightSand = Color(0xFFFFF8E1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFBFBFB))
    ) {
        // Shop Front Banner with Gradient Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFB74D), Color(0xFFFF9800))
                        )
                    )
                }
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Storefront,
                        contentDescription = "Store",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "TẠP HÓA CÔ 7",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Hệ thống quản lý sản phẩm & tra giá bằng hình ảnh thông minh",
                    fontSize = 12.sp,
                    color = Color(0xFFFFF3E0),
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(20.dp))
                // Counter badge
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Danh mục: ${allProductsState.size} mặt hàng",
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Quick Command Launcher Icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Action 1: Add new product
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setCameraMode(CameraMode.ADD_PRODUCT) }
                    .testTag("add_product_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = orangePrimary),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddAPhoto,
                        contentDescription = "Add",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Thêm Sản Phẩm",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Chụp hình rồi nhập giá",
                        fontSize = 11.sp,
                        color = Color(0xFFFFCC80),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Action 2: Price Lookup by camera
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setCameraMode(CameraMode.LOOKUP_PRODUCT) }
                    .testTag("lookup_price_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF00695C)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.ImageSearch,
                        contentDescription = "Search Price",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tra Cứu Giá",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Chụp ảnh để tra nhanh",
                        fontSize = 11.sp,
                        color = Color(0xFFB2DFDB),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Active Warning is API Key Empty
        val isApiKeyValid = remember {
            com.example.BuildConfig.GEMINI_API_KEY.isNotEmpty() &&
            com.example.BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY"
        }
        if (!isApiKeyValid) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                border = BorderStroke(1.dp, Color(0xFFFFEBAA))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Key Missing",
                        tint = Color(0xFF856404),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Chưa cấu hình API Key cho Gemini AI",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF856404)
                        )
                        Text(
                            text = "Vui lòng nhập API Key vào bảng Secrets của Google AI Studio để hệ thống nhận dạng hình ảnh hoạt động.",
                            fontSize = 11.sp,
                            color = Color(0xFF856404)
                        )
                    }
                }
            }
        }

        // Search Directory Form
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text("Search sản phẩm...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("search_field"),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = Color.Gray
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = orangePrimary,
                unfocusedBorderColor = Color(0xFFE0E0E0)
            )
        )

        // Directory List Header
        Text(
            text = "KHO MẶT HÀNG HIỆN CÓ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF7E7E7E),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )

        // Showcase Items Grid
        if (listProducts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inventory,
                        contentDescription = "Empty Directory",
                        tint = Color(0xFFE0E0E0),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "Kho hàng đang trống" else "Không tìm thấy sản phẩm này",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E8E8E)
                    )
                    Text(
                        text = if (searchQuery.isEmpty()) "Học cách thêm bằng cách nhấp 'Thêm Sản Phẩm' ở trên." else "Thử từ khóa khác hay xóa bộ lọc",
                        fontSize = 13.sp,
                        color = Color(0xFFB0B0B0),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(listProducts, key = { it.id }) { product ->
                    ProductCard(
                        product = product,
                        onDeleteClick = { productToDelete = product }
                    )
                }
            }
        }
    }

    // Modal Confirmation for item deleting
    if (productToDelete != null) {
        val selectedItem = productToDelete!!
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = { Text("Xóa sản phẩm?", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text("Bạn có chắc chắc muốn xóa mặt hàng '${selectedItem.name}' này khỏi Tạp Hóa Cô 7?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProduct(selectedItem)
                        productToDelete = null
                        Toast.makeText(context, "Đã xóa sản phẩm", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Xóa Ngay", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { productToDelete = null }) {
                    Text("Hủy bỏ")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }
}

@Composable
fun ProductCard(product: Product, onDeleteClick: () -> Unit) {
    val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale("vi", "VN")) }
    val formattedPrice = currencyFormat.format(product.price)

    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product_card_${product.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(Color(0xFFF2F2F2))
            ) {
                val imageFile = File(product.imagePath)
                if (imageFile.exists()) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = product.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.ShoppingBag,
                        contentDescription = "No Photo",
                        tint = Color.LightGray,
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                    )
                }

                // Delete Overlay Button
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.White.copy(alpha = 0.82f), CircleShape)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                Text(
                    text = product.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C2C2C),
                    maxLines = 2,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formattedPrice,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFE65100)
                )
                if (product.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = product.description,
                        fontSize = 10.sp,
                        color = Color(0xFF8E8E8E),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun CameraScanningScreen(
    viewModel: MainViewModel,
    cameraMode: StateFlow<CameraMode>,
    isAnalyzing: StateFlow<Boolean>,
    capturedImageFile: StateFlow<File?>,
    analyzedProductMatch: StateFlow<LookupMatchResult?>,
    rawProducts: List<Product>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val modeState by cameraMode.collectAsStateWithLifecycle()
    val analyzingState by isAnalyzing.collectAsStateWithLifecycle()
    val photoState by capturedImageFile.collectAsStateWithLifecycle()
    val matchState by analyzedProductMatch.collectAsStateWithLifecycle()

    // Form inputs for creation state
    val formName by viewModel.addingProductName.collectAsStateWithLifecycle()
    val formPrice by viewModel.addingProductPrice.collectAsStateWithLifecycle()
    val formDesc by viewModel.addingProductDescription.collectAsStateWithLifecycle()

    LaunchedEffect(cameraProviderFuture) {
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e("Camera", "Failed to retrieve ProcessCameraProvider: ", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Step 1: Default Mode - Capture view live scanner
        if (photoState == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (cameraProvider != null) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            try {
                                cameraProvider?.unbindAll()
                                cameraProvider?.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture
                                )
                            } catch (e: Exception) {
                                Log.e("Camera", "Lifecycle binding failure: ", e)
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                // Stylyzed scanning animation lines
                PulseScannerCrosshair(
                    title = if (modeState == CameraMode.ADD_PRODUCT) "CHỤP ẢNH SẢN PHẨM MỚI" else "ĐƯA KÍNH TRA GIÁ SẢN PHẨM",
                    description = if (modeState == CameraMode.ADD_PRODUCT) "Nhấn nút Chụp để AI gợi ý nhận diện tên & giá bán lẻ" else "Đặt sản phẩm khớp khung để tra cứu nhanh thông tin"
                )

                // Top Close Action
                IconButton(
                    onClick = { viewModel.setCameraMode(CameraMode.NONE) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(20.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Exit",
                        tint = Color.White
                    )
                }

                // Bottom Floating trigger
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(bottom = 36.dp, top = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FloatingActionButton(
                        onClick = {
                            val photoFile = File(
                                context.cacheDir,
                                "captured_product_${System.currentTimeMillis()}.jpg"
                            )
                            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                            imageCapture.takePicture(
                                outputFileOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        viewModel.onPhotoCaptured(photoFile)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        Toast.makeText(context, "Lỗi chụp ảnh: ${exception.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        },
                        containerColor = if (modeState == CameraMode.ADD_PRODUCT) Color(0xFFE65100) else Color(0xFF00695C),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("shutter_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Camera,
                            contentDescription = "Capture",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        } else {
            // Picture captured. Let's see if we are analyzing or displaying forms.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFBFBFB))
            ) {
                if (analyzingState) {
                    // Gemini matching query/generation process loading spinner
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                color = if (modeState == CameraMode.ADD_PRODUCT) Color(0xFFE65100) else Color(0xFF00695C),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = if (modeState == CameraMode.ADD_PRODUCT) "Gemini đang nhận diện nhãn sản phẩm..." else "Đang đối soát hình ảnh với kho tạp hóa...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Công nghệ trí tuệ nhân tạo đang phân tích văn bản OCR, hình học & thương hiệu của bao bì hàng hóa.",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E8E),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (modeState == CameraMode.ADD_PRODUCT) {
                    // Show full form editing to save new product
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(Color.LightGray)
                        ) {
                            AsyncImage(
                                model = photoState,
                                contentDescription = "Captured",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Hình ảnh đã lưu", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFFFF3E0), CircleShape)
                                        .size(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = "AI Suggestion",
                                        tint = Color(0xFFE65100),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Nhận Diện Bằng AI",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2C2C2C)
                                    )
                                    Text(
                                        text = "Gemini đã nỗ lực phân tích vỏ hộp sản phẩm để trích gợi ý bên dưới.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF7E7E7E)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            OutlinedTextField(
                                value = formName,
                                onValueChange = { viewModel.addingProductName.value = it },
                                label = { Text("Tên sản phẩm *") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("form_name_field"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = formPrice,
                                onValueChange = { viewModel.addingProductPrice.value = it },
                                label = { Text("Giá bán lẻ (VND) *") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("form_price_field"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = formDesc,
                                onValueChange = { viewModel.addingProductDescription.value = it },
                                label = { Text("Ghi chú chi tiết (Thương hiệu, trọng lượng...)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(30.dp))

                            // Bottom Form Control save button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.setCameraMode(CameraMode.NONE) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Quay Lại", color = Color.Gray)
                                }

                                Button(
                                    onClick = {
                                        if (formName.isBlank() || formPrice.isBlank()) {
                                            Toast.makeText(context, "Vui lòng điền đủ Tên và Giá!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.saveProduct(context, photoState!!) {
                                                Toast.makeText(context, "Đã lưu thành công!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .testTag("save_product_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Save",
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Lưu Sản Phẩm", color = Color.White)
                                }
                            }
                        }
                    }
                } else if (modeState == CameraMode.LOOKUP_PRODUCT) {
                    // Price lookup result screen container
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.setCameraMode(CameraMode.NONE) }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text(
                                text = "Kết Quả Đối Soát Trực Quan",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Match or suggest badge card block
                        if (matchState != null) {
                            val result = matchState!!
                            if (result.found) {
                                // CASE A: Successfully matches an existing catalog item
                                val matchedItem = rawProducts.firstOrNull { it.id == result.id }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(200.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .border(3.dp, Color(0xFF00897B), RoundedCornerShape(20.dp))
                                    ) {
                                        AsyncImage(
                                            model = photoState,
                                            contentDescription = "Matched",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .background(Color(0xFF00897B))
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${result.matchPercentage ?: 100}% TRÙNG KHỚP",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2F1)),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Matched OK",
                                                tint = Color(0xFF004D40),
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = matchedItem?.name ?: "Sản phẩm đã tìm thấy",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF004D40),
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            
                                            val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale("vi", "VN")) }
                                            val priceDisplay = currencyFormat.format(matchedItem?.price ?: result.suggestedPrice ?: 0L)
                                            
                                            Text(
                                                text = priceDisplay,
                                                fontSize = 26.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFF00695C)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider(color = Color(0xFFB2DFDB))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = result.reason,
                                                fontSize = 13.sp,
                                                color = Color(0xFF004E47),
                                                textAlign = TextAlign.Center,
                                                lineHeight = 18.sp
                                            )
                                        }
                                    }
                                }
                            } else {
                                // CASE B: NOT matched. Gemini suggests creation attributes
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(180.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .border(2.dp, Color(0xFFD84315), RoundedCornerShape(20.dp))
                                    ) {
                                        AsyncImage(
                                            model = photoState,
                                            contentDescription = "New Product Detected",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .background(Color(0xFFD84315))
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "CHƯA CÓ TRONG KHO",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0B2)),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Info,
                                                contentDescription = "Not in database",
                                                tint = Color(0xFFD84315),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Sản phẩm chưa có biểu giá!",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100),
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = result.reason,
                                                fontSize = 13.sp,
                                                color = Color(0xFF5D4037),
                                                textAlign = TextAlign.Center,
                                                lineHeight = 18.sp
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider(color = Color(0xFFFFCC80))
                                            Spacer(modifier = Modifier.height(16.dp))

                                            Text(
                                                text = "Đề xuất nạp sản phẩm tự động:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF5D4037)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = result.suggestedName ?: "Sản phẩm đề xuất",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF3E2723),
                                                textAlign = TextAlign.Center
                                            )
                                            
                                            val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale("vi", "VN")) }
                                            val suggestedPriceDisplay = currencyFormat.format(result.suggestedPrice ?: 0L)
                                            
                                            Text(
                                                text = "Giá đề nghị bán: $suggestedPriceDisplay",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    // Trigger rapid add button
                                    Button(
                                        onClick = {
                                            val name = result.suggestedName ?: "Sản phẩm đề xuất"
                                            val price = result.suggestedPrice ?: 0L
                                            viewModel.saveSuggestedProduct(context, name, price) {
                                                Toast.makeText(context, "Đã lưu sản phẩm vào danh mục!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("add_suggested_product_button")
                                    ) {
                                        Icon(imageVector = Icons.Filled.CloudUpload, contentDescription = "Add suggested")
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Duyệt Thêm Vào Kho Ngay", color = Color.White)
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Lỗi xảy ra hoặc không load được thông tin.", color = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Controls buttons to retry or exit
                        OutlinedButton(
                            onClick = { viewModel.setCameraMode(CameraMode.NONE) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Hoàn Thành / Trở Về", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PulseScannerCrosshair(
    title: String,
    description: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_laser")
    val deltaY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_laser_height"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Upper text cues
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }

        // Translucent square hole target box in center
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            val sizePx = remember { 260.dp }

            Box(
                modifier = Modifier
                    .size(sizePx)
                    .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                // Laser animation line overlay inside bounding target
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.015f)
                        .align(Alignment.TopCenter)
                        .offset(y = (260.dp * deltaY))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color(0xFF00E676), Color.Transparent)
                            )
                        )
                )
            }
        }
    }
}

