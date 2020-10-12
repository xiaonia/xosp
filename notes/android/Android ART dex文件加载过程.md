### Android ART dex加载过程



####  相关链接

[dalvik_system_DexFile.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/native/dalvik_system_DexFile.cc)

[oat_file_manager.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/oat_file_manager.cc)

[oat_file_assistant.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/oat_file_assistant.cc)

[oat_file.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/oat_file.cc)

[file_utils.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/base/file_utils.cc)

[art_dex_file_loader.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/dex/art_dex_file_loader.cc)

[dex_file_loader.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/libdexfile/dex/dex_file_loader.cc)

[class_linker.cc](https://android.googlesource.com/platform/art/+/refs/tags/android-9.0.0_r60/runtime/class_linker.cc)

#### __.art / .odex / .vdex__

* __.vdex__: contains the uncompressed DEX code of the APK, with some additional metadata to speed up verification. （dex file）

* __.odex__: contains AOT compiled code for methods in the APK. （oat file）

* __.art (optional)__: contains ART internal representations of some strings and classes listed in the APK, used to speed application startup. （app iamge file）

  

#### openDexFile

##### openDexFileNative

```cpp
// DexFile_openDexFileNative                   #dalvik_system_DexFile.cc
// --OatFileManager::OpenDexFilesFromOat       #oat_file_manager.cc
// ----OatFileAssistant::OatFileInfo::GetFile()#oat_file_assistant.cc
// ------OatFile::Open                         #oat_file.cc
// --------OatFileBase::OpenOatFile            #oat_file.cc
// ---~ClassLinker::AddImageSpace              #class_linker.cc
// ---~OatFileAssistant::LoadDexFiles          #oat_file_assistant.cc
// ---~ArtDexFileLoader::Open                  #art_dex_file_loader.cc 
```

##### load oat file (.odex and .vdex)

```cpp
// OatFileManager::OpenDexFilesFromOat            #oat_file_manager.cc
// --OatFileAssistant::GetBestOatFile()           #oat_file_assistant.cc
// ----OatFileAssistant::GetBestInfo()            #oat_file_assistant.cc
// --OatFileAssistant::OatFileInfo::GetFile()     #oat_file_assistant.cc
// ----OatFile::Open                              #oat_file.cc
// ------OatFileBase::OpenOatFile                 #oat_file.cc
// --------OatFileBase::Load                      #oat_file.cc（.odex）
// --------OatFileBase::ComputeFields             #oat_file.cc
// --------OatFileBase::LoadVdex                  #oat_file.cc（.vdex）
// --------OatFileBase::Setup                     #oat_file.cc 
```

##### load dex files through app image  (.art & .odex & .vdex)

```cpp
// OatFileManager::OpenDexFilesFromOat            #oat_file_manager.cc
// --OatFileAssistant::OpenImageSpace             #oat_file_assistant.cc
// --ClassLinker::AddImageSpace                   #class_linker.cc
// ----OpenOatDexFile                             #class_linker.cc
// ------OatFile::GetOatDexFile                   #oat_file.cc
// ------OatFile::OatDexFile::OpenDexFile         #oat_file.cc
```

##### load dex files through oat file (.odex & .vdex)

```cpp
// OatFileAssistant::LoadDexFiles               #oat_file_assistant.cc
// --OatFile::GetOatDexFile                     #oat_file.cc
// --OatFile::OatDexFile::OpenDexFile           #oat_file.cc
// --DexFileLoader::GetMultiDexLocation         #dex_file_loader.cc
// --OatFile::GetOatDexFile                     #oat_file.cc
// --OatFile::OatDexFile::OpenDexFile           #oat_file.cc
```

##### load dex files through zip file (.apk)

```cpp
// ArtDexFileLoader::Open                        #art_dex_file_loader.cc 
// --ArtDexFileLoader::OpenZip                   #art_dex_file_loader.cc 
// ----ArtDexFileLoader::OpenAllDexFilesFromZip  #art_dex_file_loader.cc 
// ------ArtDexFileLoader::OpenOneDexFileFromZip #art_dex_file_loader.cc 
// --------DexFileLoader::OpenCommon             #dex_file_loader.cc
```

