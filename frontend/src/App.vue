<template>
  <div id="app">
    <div class="container">
      <h1 class="title">📄 PDF 页面倾斜校正系统</h1>

      <div class="upload-section">
        <PdfUpload
          v-model="selectedFile"
          accept=".pdf"
          :processing="isProcessing"
          @file-select="onFileSelect"
        />

        <div v-if="selectedFile && !isProcessing && !correctedFile" class="action-buttons">
          <PdfButton variant="outline-primary" @click="showPreview" fullWidth>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="20" height="20">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
              <circle cx="12" cy="12" r="3"></circle>
            </svg>
            预览原文件
          </PdfButton>
          <PdfButton variant="success" @click="uploadAndCorrect" :loading="isProcessing" fullWidth>
            开始校正
          </PdfButton>
        </div>
        
        <!-- 新增的PDF书签功能按钮 -->
        <div v-if="selectedFile && !isProcessing" class="bookmark-buttons">
          <PdfButton variant="outline-secondary" @click="previewBookmarks" fullWidth>
            预览目录结构
          </PdfButton>
          <PdfButton variant="primary" @click="addBookmarksToPdf" :loading="isBookmarkProcessing" fullWidth>
            添加书签并下载
          </PdfButton>
        </div>
      </div>

      <PdfProgress
        :show="isProcessing || isBookmarkProcessing"
        type="bar"
        :progress="progressValue"
        :message="progressMessage"
      />

      <div v-if="processSteps.length > 0" class="process-steps">
        <h3>处理进度:</h3>
        <ul>
          <li 
            v-for="(step, index) in processSteps" 
            :key="index" 
            :class="{ 'completed': step.completed, 'current': step.current, 'total-time': step.isTotalTime }"
          >
            {{ step.message }}
          </li>
        </ul>
      </div>

      <div v-if="errorMessage" class="error-message">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
          <circle cx="12" cy="12" r="10"></circle>
          <line x1="12" y1="8" x2="12" y2="12"></line>
          <line x1="12" y1="16" x2="12.01" y2="16"></line>
        </svg>
        {{ errorMessage }}
      </div>

      <div v-if="successMessage" class="toast-success">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
          <polyline points="22 4 12 14.01 9 11.01"></polyline>
        </svg>
        {{ successMessage }}
      </div>

      <div v-if="correctedFile" class="success-section">
        <div class="success-message">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
            <polyline points="22 4 12 14.01 9 11.01"></polyline>
          </svg>
          <span>校正完成！</span>
        </div>
        
        <div class="correction-info" v-if="processingTime > 0">
          <div>
            <div>
              <p>各页面的倾斜角度详情:</p>
              <ul>
                <li v-for="(angle, index) in pageAngles" :key="index">
                  第{{ index + 1 }}页: {{ angle.toFixed(2) }}°
                </li>
              </ul>
            </div>
          </div>
          <p>处理耗时: {{ processingTime.toFixed(2) }}s</p>
        </div>

        <div class="action-buttons">
          <PdfButton variant="outline-primary" @click="showCompareView" fullWidth>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="20" height="20">
              <rect x="3" y="3" width="7" height="7"></rect>
              <rect x="14" y="3" width="7" height="7"></rect>
              <rect x="14" y="14" width="7" height="7"></rect>
              <rect x="3" y="14" width="7" height="7"></rect>
            </svg>
            对比预览
          </PdfButton>
          <PdfButton variant="primary" @click="downloadFile" fullWidth>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="20" height="20">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="7 10 12 15 17 10"></polyline>
              <line x1="12" y1="15" x2="12" y2="3"></line>
            </svg>
            下载校正后的PDF
          </PdfButton>
        </div>

        <PdfButton variant="secondary" @click="reset" fullWidth class="reset-button">
          处理新文件
        </PdfButton>
      </div>
    </div>

    <PdfModal v-model:visible="showPreviewModal" :title="previewTitle" content-class="preview-modal">
      <div v-if="compareMode" class="compare-container">
        <div class="preview-panel">
          <h3>原始文件</h3>
          <div class="pdf-viewer">
            <iframe :src="originalPdfUrl" v-if="originalPdfUrl"></iframe>
            <div v-else>加载中...</div>
          </div>
        </div>

        <div class="preview-panel">
          <h3>校正后文件</h3>
          <div class="pdf-viewer">
            <iframe :src="correctedPdfUrl" v-if="correctedPdfUrl"></iframe>
            <div v-else>加载中...</div>
          </div>
        </div>
      </div>

      <div v-else class="single-preview">
        <div class="pdf-viewer">
          <iframe :src="previewPdfUrl" v-if="previewPdfUrl"></iframe>
          <div v-else>加载中...</div>
        </div>
      </div>
    </PdfModal>
    
    <!-- 目录结构预览模态框 -->
    <PdfModal v-model:visible="showTocModal" title="PDF目录结构预览" content-class="toc-modal">
      <!-- 加载状态 -->
      <div v-if="isBookmarkProcessing" class="toc-loading-container">
        <div class="loading-spinner"></div>
        <div class="loading-text">正在分析目录结构...</div>
      </div>

      <!-- 内容区域 -->
      <div v-else class="toc-container">
        <!-- 左侧 PDF 预览 -->
        <div class="toc-pdf-preview">
          <div class="pdf-viewer-body">
            <VuePdfApp 
              v-if="previewPdfUrl" 
              :key="previewPdfUrl"
              :pdf="previewPdfUrl"
              theme="light"
              :config="pdfAppConfig"
              ref="pdfApp"
              class="clean-pdf-viewer"
              :page="currentPdfPage"
              @pages-rendered="() => {}"
              @page-changed="(page) => currentPdfPage = page"
              @after-created="handlePdfAppCreated"
            />
            <div v-else class="loading-placeholder">加载 PDF 中...</div>
          </div>
        </div>

        <!-- 右侧 目录编辑 -->
        <div class="toc-editor-panel">
          <PdfTocEditor 
            v-if="tocData && tocData.length > 0" 
            v-model="tocData" 
            @page-focus="handlePageFocus"
          />
          <div v-else-if="tocContent === '未获取到目录结构'" class="error-text">{{ tocContent }}</div>
          <div v-else class="empty-text">暂无目录数据</div>
        </div>
      </div>
      <template #footer>
        <div class="toc-modal-footer">
          <div class="footer-info" v-if="tocData.length > 0">
            共 {{ tocData.length }} 个章节
          </div>
          <PdfButton variant="primary" @click="addBookmarksToPdf" :loading="isBookmarkProcessing">
            确认并生成PDF
          </PdfButton>
        </div>
      </template>
    </PdfModal>
  </div>
</template>

<script>
import axios from 'axios';
import PdfUpload from './components/PdfUpload.vue';
import PdfButton from './components/PdfButton.vue';
import PdfProgress from './components/PdfProgress.vue';
import PdfModal from './components/PdfModal.vue';
import PdfTocEditor from './components/PdfTocEditor.vue';
import VuePdfApp from 'vue3-pdf-app';
import "vue3-pdf-app/dist/icons/main.css";

export default {
  name: 'App',
  components: {
    PdfUpload,
    PdfButton,
    PdfProgress,
    PdfModal,
    PdfTocEditor,
    VuePdfApp
  },
  data() {
    return {
      selectedFile: null,
      isProcessing: false,
      isBookmarkProcessing: false,
      correctedFile: null,
      errorMessage: '',
      successMessage: '',
      showPreviewModal: false,
      showTocModal: false,
      compareMode: false,
      previewTitle: '',
      originalPdfUrl: null,
      correctedPdfUrl: null,
      previewPdfUrl: null,
      detectedAngle: null,
      pageAngles: [],
      processingTime: 0,
      startTime: 0,
      progressValue: 0,
      progressInterval: null,
      progressMessage: '',
      processSteps: [], // 处理步骤数组
      eventSource: null,
      totalBatches: 0, // 总批次数
      currentBatch: 0,  // 当前批次数
      tocContent: null,
      tocData: [], // 解析后的目录数据
      lastProcessedFile: null, // 用于缓存目录预览的文件引用
      currentPdfPage: 1,
      pdfAppInstance: null, // 保存 PDF viewer 实例
      pdfAppConfig: {
        sidebar: false,
        toolbar: {
          toolbarViewerLeft: {
            findbar: false,
            previous: true,
            next: true,
            pageNumber: true,
          },
          toolbarViewerRight: {
            presentationMode: false,
            openFile: false,
            print: false,
            download: false,
            viewBookmark: false,
          }
        }
      }
    };
  },
  methods: {
    onFileSelect() {
      this.errorMessage = '';
      this.successMessage = '';
      // 文件改变时，清空目录缓存
      this.tocContent = null;
      this.tocData = [];
      this.lastProcessedFile = null;
    },

    showSuccessMessage(message) {
      this.successMessage = message;
      this.errorMessage = '';
      // 3秒后自动消失
      setTimeout(() => {
        this.successMessage = '';
      }, 3000);
    },

    showPreview() {
      if (!this.selectedFile) {
        this.errorMessage = '未选择文件';
        return;
      }

      this.compareMode = false;
      this.previewTitle = '原始PDF预览';
      this.showPreviewModal = true;

      this.previewPdfUrl = URL.createObjectURL(this.selectedFile);
    },

    async showCompareView() {
      if (!this.selectedFile) {
        this.errorMessage = '未选择文件';
        return;
      }

      if (!this.correctedFile) {
        this.errorMessage = '请先处理文件再进行对比预览';
        return;
      }

      this.compareMode = true;
      this.previewTitle = '校正前后对比';
      this.showPreviewModal = true;

      this.originalPdfUrl = URL.createObjectURL(this.selectedFile);

      try {
        const response = await axios.get(
          `http://localhost:8080/api/pdf/download/${this.correctedFile}`,
          { responseType: 'blob' }
        );
        
        this.correctedPdfUrl = URL.createObjectURL(response.data);
      } catch (error) {
        console.error('加载校正文件失败:', error);
        this.errorMessage = '加载校正文件失败: ' + (error.message || '未知错误');
      }
    },

    closePreview() {
      this.showPreviewModal = false;
      
      if (this.originalPdfUrl) {
        URL.revokeObjectURL(this.originalPdfUrl);
        this.originalPdfUrl = null;
      }
      
      if (this.correctedPdfUrl) {
        URL.revokeObjectURL(this.correctedPdfUrl);
        this.correctedPdfUrl = null;
      }
      
      if (this.previewPdfUrl) {
        URL.revokeObjectURL(this.previewPdfUrl);
        this.previewPdfUrl = null;
      }
    },

    // 添加处理步骤
    addProcessStep(message) {
      // 检查是否是总用时信息（包括批次总用时和最终总用时）
      if (message.startsWith('总用时:') || message.includes(' 总用时:')) {
        // 对总用时信息加粗显示
        this.processSteps.push({
          message: message,
          completed: true,
          current: false,
          isTotalTime: true // 标记为总用时信息
        });
        
        // 提取处理时间（秒）
        const timeMatch = message.match(/总用时:\s*([\d.]+)s/);
        if (timeMatch && timeMatch[1]) {
          this.processingTime = parseFloat(timeMatch[1]);
        }
      } else {
        this.processSteps.push({
          message: message,
          completed: true,
          current: false,
          isTotalTime: false
        });
      }
    },

    // 更新当前处理步骤
    updateCurrentStep(message) {
      // 将之前的步骤标记为完成
      this.processSteps.forEach(step => {
        step.current = false;
        step.completed = true;
      });
      
      // 添加新的当前步骤
      this.processSteps.push({
        message: message,
        completed: false,
        current: true,
        isTotalTime: false
      });
    },

    async uploadAndCorrect() {
      if (!this.selectedFile) {
        this.errorMessage = '未选择文件';
        return;
      }

      this.isProcessing = true;
      this.errorMessage = '';
      this.startTime = Date.now();
      this.progressValue = 0;
      this.processSteps = []; // 清空之前的步骤
      this.detectedAngle = null; // 重置角度显示
      this.totalBatches = 0; // 重置批次计数
      this.currentBatch = 0; // 重置当前批次
      
      // 建立SSE连接以接收实时进度更新
      this.eventSource = new EventSource('http://localhost:8080/api/pdf/progress');
      
      this.eventSource.addEventListener('progress', (event) => {
        const message = event.data;
        this.addProcessStep(message);
        
        // 解析批次信息并更新进度
        const batchRegex = /批次 (\d+)\/(\d+) .*/;
        const match = message.match(batchRegex);
        if (match) {
          this.currentBatch = parseInt(match[1]);
          this.totalBatches = parseInt(match[2]);
          // 计算进度百分比 (使用更平滑的计算方式)
          if (this.totalBatches > 0) {
            // 为了让进度条看起来更平滑，我们使用 90% 作为批次处理的最大值
            // 剩下的10%留给最后的保存操作
            const batchProgress = (this.currentBatch - 1) / this.totalBatches;
            this.progressValue = Math.round(batchProgress * 90);
          }
        }
        
        // 检查是否是总用时信息或者处理完成信息
        if (message.startsWith('总用时:') || message === '处理完成') {
          // 确保进度条达到100%
          this.progressValue = 100;
        }
      });
      
      this.eventSource.addEventListener('angle', (event) => {
        this.detectedAngle = parseFloat(event.data);
      });
      
      this.eventSource.onerror = (error) => {
        console.error('SSE连接错误:', error);
      };

      // 显示开始处理信息
      this.updateCurrentStep('开始处理PDF文件...');

      const formData = new FormData();
      formData.append('file', this.selectedFile);

      try {
        const response = await axios.post('http://localhost:8080/api/pdf/upload', formData, {
          headers: {
            'Content-Type': 'multipart/form-data'
          }
        });

        // 确保进度条达到100%
        this.progressValue = 100;

        if (response.data.success) {
          this.correctedFile = response.data.fileName;
          this.pageAngles = response.data.pageAngles || [];
          
          if (this.pageAngles && this.pageAngles.length > 0) {
            const sum = this.pageAngles.reduce((a, b) => a + b, 0);
            this.detectedAngle = sum / this.pageAngles.length;
          } else {
            this.detectedAngle = response.data.angle || 0;
          }
          
          // 完成所有步骤
          this.processSteps.forEach(step => {
            step.current = false;
            step.completed = true;
          });
        } else {
          this.errorMessage = response.data.message || '处理失败';
        }
      } catch (error) {
        console.error('文件上传失败:', error);
        
        if (error.response && error.response.data && error.response.data.message) {
          this.errorMessage = error.response.data.message;
        } else if (error.response) {
          this.errorMessage = '服务器处理失败: ' + (error.response.statusText || '未知错误');
        } else if (error.request) {
          this.errorMessage = '无法连接到服务器，请检查网络连接或服务器状态';
        } else {
          this.errorMessage = '上传失败: ' + (error.message || '未知错误');
        }
      } finally {
        this.isProcessing = false;
        this.progressValue = 100;
        
        // 关闭SSE连接
        if (this.eventSource) {
          this.eventSource.close();
          this.eventSource = null;
        }
      }
    },

    async downloadFile() {
      if (!this.correctedFile) return;

      try {
        const response = await axios.get(
          `http://localhost:8080/api/pdf/download/${this.correctedFile}`,
          { responseType: 'blob' }
        );
        
        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', this.correctedFile);
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.URL.revokeObjectURL(url);
      } catch (error) {
        console.error('下载失败:', error);
        if (error.response && error.response.headers['error-message']) {
          this.errorMessage = '下载失败: ' + error.response.headers['error-message'];
        } else {
          this.errorMessage = '下载失败，请重试';
        }
      }
    },

    // 预览PDF目录结构
    async previewBookmarks() {
      if (!this.selectedFile) {
        this.errorMessage = '未选择文件';
        return;
      }

      // 如果已经有缓存且文件未变，直接显示
      if (this.tocContent && this.lastProcessedFile === this.selectedFile) {
        this.showTocModal = true;
        return;
      }

      this.isBookmarkProcessing = true;
      this.errorMessage = '';
      this.tocContent = null;
      this.showTocModal = true;
      
      // 更新预览 URL
      if (this.previewPdfUrl) {
        URL.revokeObjectURL(this.previewPdfUrl);
      }
      if (this.selectedFile) {
        this.previewPdfUrl = URL.createObjectURL(this.selectedFile);
      }

      const formData = new FormData();
      formData.append('file', this.selectedFile);
      try {
        const response = await axios.post('http://localhost:8080/api/pdf/preview-toc', formData, {
          headers: {
            'Content-Type': 'multipart/form-data'
          }
        });

        if (response.data) {
          let parsedData = null;
          // 检查返回的数据是否已经是JSON对象
          if (typeof response.data === 'object') {
            parsedData = response.data;
          } else {
            // 如果是字符串，则尝试解析为JSON
            try {
              parsedData = JSON.parse(response.data);
            } catch (parseError) {
              // 如果解析失败，直接显示原始数据
              this.tocContent = response.data;
            }
          }

          if (parsedData) {
            // 处理后端返回的结构
            if (parsedData.tableOfContents) {
              this.tocData = parsedData.tableOfContents;
            } else if (Array.isArray(parsedData)) {
              this.tocData = parsedData;
            } else {
              this.tocData = [];
            }
            this.tocContent = JSON.stringify(parsedData, null, 2);
          }
          
          // 缓存当前文件引用
          this.lastProcessedFile = this.selectedFile;
        } else {
          this.tocContent = '未获取到目录结构';
          this.tocData = [];
        }
      } catch (error) {
        console.error('预览目录结构失败:', error);
        if (error.response) {
          // 服务器返回了错误响应
          this.errorMessage = '预览目录结构失败: ' + (error.response.data || error.response.statusText || '服务器错误');
        } else if (error.request) {
          // 请求已发出但没有收到响应
          this.errorMessage = '预览目录结构失败: 无法连接到服务器，请检查网络连接或服务器状态';
        } else {
          // 其他错误
          this.errorMessage = '预览目录结构失败: ' + (error.message || '未知错误');
        }
        this.showTocModal = false;
      } finally {
        this.isBookmarkProcessing = false;
      }
    },

    handlePdfAppCreated(pdfApp) {
      console.log('PDF App created:', pdfApp);
      this.pdfAppInstance = pdfApp;
    },

    async handlePageFocus(page) {
      // 尝试跳转到指定页面
      if (page > 0) {
        const pageNum = parseInt(page);
        console.log('尝试跳转到页面:', pageNum);
        
        // 1. 更新绑定的 prop (保持状态同步)
        this.currentPdfPage = pageNum;
        
        // 2. 使用实例直接跳转 (最可靠的方式)
        if (this.pdfAppInstance) {
          try {
            console.log('使用 pdfAppInstance 跳转到:', pageNum);
            this.pdfAppInstance.page = pageNum;
          } catch (e) {
            console.error('实例跳转失败:', e);
          }
        } else {
          console.warn('pdfAppInstance 未就绪');
          // 备选：尝试通过 ref 获取 (如果 after-created 还没触发)
          if (this.$refs.pdfApp && this.$refs.pdfApp.pdfApp) {
             this.$refs.pdfApp.pdfApp.page = pageNum;
          }
        }
      }
    },

    // 添加书签到PDF并下载
    async addBookmarksToPdf() {
      if (!this.selectedFile) {
        this.errorMessage = '未选择文件';
        return;
      }

      this.isBookmarkProcessing = true;
      this.errorMessage = '';
      this.progressValue = 0;

      // 模拟进度条
      if (this.progressInterval) clearInterval(this.progressInterval);
      this.progressInterval = setInterval(() => {
        if (this.progressValue < 90) {
          this.progressValue += Math.floor(Math.random() * 10);
          if (this.progressValue > 90) this.progressValue = 90;
        }
      }, 200);

      const formData = new FormData();
      formData.append('file', this.selectedFile);

      // 优先使用当前编辑过的 tocData
      if (this.tocData && this.tocData.length > 0 && this.lastProcessedFile === this.selectedFile) {
        // 直接发送数组，后端支持
        formData.append('tocJson', JSON.stringify(this.tocData));
      } 
      // 如果没有编辑过的数据，但有缓存的原始内容（且文件未变），则使用缓存
      else if (this.tocContent && this.lastProcessedFile === this.selectedFile) {
        formData.append('tocJson', this.tocContent);
      }

      try {
        const response = await axios.post('http://localhost:8080/api/pdf/add-bookmarks', formData, {
          responseType: 'blob',
          headers: {
            'Content-Type': 'multipart/form-data'
          }
        });
        
        this.progressValue = 100;

        // 获取文件名
        // const disposition = response.headers['content-disposition'];
        // let filename = 'bookmarked_document.pdf';
        // if (disposition && disposition.indexOf('attachment') !== -1) {
        //   const filenameRegex = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/;
        //   const matches = filenameRegex.exec(disposition);
        //   if (matches != null && matches[1]) {
        //     filename = matches[1].replace(/['"]/g, '');
        //   }
        // }
        
        // 使用原文件名 + 后缀
        let filename = 'bookmarked_document.pdf';
        if (this.selectedFile && this.selectedFile.name) {
          const originalName = this.selectedFile.name;
          const lastDotIndex = originalName.lastIndexOf('.');
          if (lastDotIndex !== -1) {
            filename = originalName.substring(0, lastDotIndex) + '_bookmarked.pdf';
          } else {
            filename = originalName + '_bookmarked.pdf';
          }
        }

        // 下载文件
        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', filename);
        document.body.appendChild(link);
        link.click();

        // 清理
        window.URL.revokeObjectURL(url);
        if (this.progressInterval) clearInterval(this.progressInterval);
        this.isBookmarkProcessing = false;
        this.showSuccessMessage('PDF生成成功，正在下载...');

      } catch (error) {
        console.error('添加书签失败:', error);
        this.errorMessage = '添加书签失败: ' + (error.response ? await error.response.data.text() : error.message);
        this.isBookmarkProcessing = false;
        if (this.progressInterval) clearInterval(this.progressInterval);
      }
    },

    reset() {
      this.selectedFile = null;
      this.correctedFile = null;
      this.pageAngles = [];
      this.errorMessage = '';
      this.progressValue = 0;
      this.detectedAngle = null;
      this.processSteps = [];
      this.totalBatches = 0;
      this.currentBatch = 0;
      this.tocContent = null;
      this.lastProcessedFile = null;
      
      if (this.previewPdfUrl) {
        URL.revokeObjectURL(this.previewPdfUrl);
        this.previewPdfUrl = null;
      }
      
      // 关闭事件源
      if (this.eventSource) {
        this.eventSource.close();
        this.eventSource = null;
      }
    }
  },

  watch: {
    showTocModal(val) {
      if (!val) {
        this.pdfAppInstance = null;
      }
    }
  },
  
  beforeUnmount() {
    if (this.progressInterval) {
      clearInterval(this.progressInterval);
    }
    
    // 关闭事件源
    if (this.eventSource) {
      this.eventSource.close();
    }

    // 清理 PDF 预览 URL
    if (this.previewPdfUrl) {
      URL.revokeObjectURL(this.previewPdfUrl);
      this.previewPdfUrl = null;
    }
  }
};
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

/* 保证根元素在需要时可以滚动，不需要时不显示滚动条 */
html, body {
  width: 100%;
  min-height: 100%;
  overflow-y: auto; /* 内容超出时显示滚动条，不超出时不显示 */
  margin: 0;
  padding: 0;
}

/* 整个应用区域 */
#app {
  width: 100%;
  min-height: 100vh; /* 至少占满整个视口高度 */
  background-image: url('@/images/背景.webp');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  background-attachment: fixed;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
}

/* 主内容区域 */
.container {
  width: 100%;
  max-width: 800px;
  min-height: calc(100vh - 60px);
  margin-top: 30px;
  padding: 20px 20px 40px;
  scrollbar-width: none; /* Firefox 隐藏滚动条 */
}

.container::-webkit-scrollbar {
  display: none; /* Chrome 隐藏滚动条 */
}

.title {
  text-align: center;
  color: white;
  font-size: 2rem;
  margin-bottom: 40px;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.2);
}

.upload-section {
  background: white;
  border-radius: 12px;
  padding: 30px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.2);
}

.action-buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
  margin-top: 30px;
}

.bookmark-buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

@media (max-width: 768px) {
  .action-buttons,
  .bookmark-buttons {
    grid-template-columns: 1fr;
  }

  .title {
    font-size: 1.5rem;
    margin-bottom: 30px;
  }

  .upload-section {
    padding: 20px;
  }
}

.error-message {
  margin-top: 30px;
  padding: 15px;
  background: #fee;
  border: 1px solid #fcc;
  border-radius: 8px;
  color: #c33;
  display: flex;
  align-items: center;
  gap: 10px;
}

.error-message svg {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  stroke-width: 2;
}

.toast-success {
  margin-top: 30px;
  padding: 15px;
  background: #e6fffa;
  border: 1px solid #b2f5ea;
  border-radius: 8px;
  color: #2c7a7b;
  display: flex;
  align-items: center;
  gap: 10px;
}

.toast-success svg {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  stroke-width: 2;
}

.success-section {
  margin-top: 30px;
  background: white;
  border-radius: 12px;
  padding: 30px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.2);
}

.success-message {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #10b981;
  font-size: 1.2rem;
  font-weight: 600;
  margin-bottom: 25px;
}

.success-message svg {
  width: 32px;
  height: 32px;
  stroke-width: 2;
}

.correction-info {
  text-align: center;
  margin: 20px 0;
  padding: 15px;
  background: #f0f9ff;
  border-radius: 8px;
  color: #0369a1;
  font-weight: 500;
}

.correction-info > div > div {
  margin-bottom: 15px;
}

.correction-info ul {
  list-style-type: none;
  padding: 0;
  margin: 10px 0;
  text-align: left;
  display: inline-block;
}

.reset-button {
  margin-top: 20px;
}

/* 预览模态框 */
.preview-modal {
  width: 95vw;
  height: 90vh;
}

.compare-container {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  min-height: 800px;
}

.preview-panel h3 {
  text-align: center;
  color: #667eea;
  margin-bottom: 15px;
  font-size: 1.2rem;
}

.pdf-viewer {
  background: #f9fafb;
  border-radius: 8px;
  padding: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 800px;
  overflow: auto;
}

.pdf-viewer iframe {
  width: 100%;
  height: 800px;
  border: none;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  border-radius: 4px;
}

/* 步骤条 */
.process-steps {
  background: rgba(255, 255, 255, 0.9);
  border-radius: 8px;
  padding: 15px;
  margin: 20px 0;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.process-steps h3 {
  color: #333;
  margin-bottom: 10px;
  text-align: center;
}

.process-steps ul {
  list-style-type: none;
  padding-left: 0;
  margin: 0;
}

.process-steps li {
  padding: 8px 0 8px 30px;
  color: #555;
  font-family: 'Courier New', monospace;
  border-bottom: 1px solid #eee;
  position: relative;
}

.process-steps li.completed::before {
  content: "✓";
  position: absolute;
  left: 10px;
  color: #10b981;
  font-weight: bold;
}

.process-steps li.current::before {
  content: "→";
  position: absolute;
  left: 10px;
  color: #667eea;
  font-weight: bold;
  animation: blink 1s infinite;
}

.process-steps li.total-time {
  font-weight: bold;
  color: #333;
}

@keyframes blink {
  50% {
    opacity: 0.5;
  }
}

/* 目录结构预览模态框 */
.toc-modal {
  width: 95vw;
  height: 90vh;
  display: flex;
  flex-direction: column;
}

.toc-loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #5f6368;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #f3f3f3;
  border-top: 3px solid #1a73e8;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 16px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.toc-container {
  display: flex;
  height: 100%; /* 占满 modal body */
  overflow: hidden;
}

.toc-pdf-preview {
  flex: 1;
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  background: #525659;
  overflow: hidden; /* 确保 PDF 容器不溢出 */
}

.pdf-viewer-header {
  padding: 8px 16px;
  background: #323639;
  color: #f1f3f4;
  font-size: 12px;
  font-weight: 500;
  flex-shrink: 0;
}

.pdf-viewer-body {
  flex: 1;
  position: relative;
  overflow: hidden; /* vue3-pdf-app 会处理自己的滚动 */
}

.pdf-viewer-body iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.loading-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #fff;
}

.clean-pdf-viewer {
  height: 100%;
  width: 100%;
}

/* 隐藏 vue3-pdf-app 的部分多余样式，使其更像原生 */
:deep(.toolbar) {
  background-color: #f5f5f5 !important;
  border-bottom: 1px solid #e0e0e0 !important;
}

.toc-editor-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
  min-width: 500px; /* 保证编辑器有足够宽度 */
  overflow: hidden; /* 禁止自身滚动，由子组件处理 */
}

.toc-modal-footer {
  display: flex;
  justify-content: space-between; /* 两端对齐 */
  align-items: center;
  width: 100%; /* 占满宽度 */
  /* 移除多余的 padding 和 border，因为父组件已经有了 */
  padding: 0;
  border: none;
  background: transparent;
}

.footer-info {
  color: #5f6368;
  font-size: 14px;
}

.error-text {
  padding: 40px;
  color: #d93025;
  text-align: center;
}

.empty-text, .loading-text {
  padding: 40px;
  text-align: center;
  color: #5f6368;
}
</style>
