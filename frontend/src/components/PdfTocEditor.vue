<template>
  <div class="pdf-toc-editor">
    <div class="toc-header">
      <div class="header-title">目录结构</div>
      <div class="header-actions">
        <button class="btn-text" @click="expandAll">展开全部</button>
        <button class="btn-text" @click="collapseAll">折叠全部</button>
      </div>
    </div>
    
    <div class="toc-list-container">
      <div class="toc-list">
        <div 
          v-for="row in visibleRows" 
          :key="row.originalIndex" 
          class="toc-row"
          :class="{ 
            'active': activeIndex === row.originalIndex,
            'drag-over-top': dragOverIndex === row.originalIndex && dragPosition === 'top',
            'drag-over-bottom': dragOverIndex === row.originalIndex && dragPosition === 'bottom',
            'is-dragging': draggingIndex === row.originalIndex
          }"
          @click="handleRowClick(row.originalIndex); $emit('page-focus', row.item.page)"
          @dragover.prevent="onDragOver($event, row.originalIndex)"
          @drop="onDrop($event, row.originalIndex)"
        >
          <!-- 缩进控制区 -->
          <div class="indent-control" :style="{ width: (row.item.level * 24) + 'px' }">
            <!-- 缩进线 -->
            <div class="indent-line" v-for="n in (row.item.level - 1)" :key="n"></div>
            
            <!-- 折叠/展开图标 (仅在最后一级缩进显示) -->
            <div class="indent-toggle" @click.stop="toggleExpand(row.originalIndex)">
              <svg v-if="row.hasChildren" :class="{ 'rotated': row.isExpanded }" viewBox="0 0 24 24" width="16" height="16">
                <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" fill="currentColor"/>
              </svg>
            </div>
          </div>

          <!-- 内容区 -->
          <div class="toc-content-wrapper">
            <!-- 移动控制 -->
            <div class="move-controls">
              <button 
                class="btn-icon mini" 
                @click.stop="changeLevel(row.originalIndex, -1)" 
                :disabled="row.item.level <= 1"
                title="向左缩进 (升级)"
              >
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z" fill="currentColor"/></svg>
              </button>
              <button 
                class="btn-icon mini" 
                @click.stop="changeLevel(row.originalIndex, 1)" 
                :disabled="row.item.level >= 6"
                title="向右缩进 (降级)"
              >
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" fill="currentColor"/></svg>
              </button>
            </div>

            <!-- 标题输入 -->
            <div class="title-input-wrapper">
              <input 
                type="text" 
                v-model="row.item.title" 
                class="clean-input title-input"
                :class="{ 'is-chapter': row.item.level === 1 }"
                @change="emitUpdate"
                @focus="$emit('page-focus', row.item.page)"
                @click.stop
                placeholder="输入标题..."
              >
            </div>

            <!-- 页码输入 -->
            <div class="page-input-wrapper">
              <input 
                type="number" 
                v-model.number="row.item.page" 
                class="clean-input page-input" 
                min="1"
                @change="emitUpdate"
                @focus="$emit('page-focus', row.item.page)"
                @click.stop
                placeholder="页码"
              >
            </div>

            <!-- 删除按钮 -->
            <button class="btn-icon delete" @click.stop="removeItem(row.originalIndex)" title="删除">
              <svg viewBox="0 0 24 24" width="18" height="18"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" fill="currentColor"/></svg>
            </button>

            <!-- 拖拽手柄 -->
            <div 
              class="drag-handle" 
              draggable="true" 
              @dragstart="onDragStart($event, row.originalIndex)"
              @dragend="onDragEnd"
              title="拖动排序"
            >
              <svg viewBox="0 0 24 24" width="16" height="16"><path d="M11 18c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm-2-8c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0-6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6 4c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z" fill="currentColor"/></svg>
            </div>
          </div>
        </div>
      </div>

      <div v-if="localToc.length === 0" class="empty-state">
        <div class="empty-icon">📑</div>
        <p>暂无目录条目</p>
        <button class="btn-primary" @click="addItem">添加第一章</button>
      </div>
    </div>
    
    <div class="toc-fab">
      <button class="fab-button" @click="addItem" title="添加新条目">
        <svg viewBox="0 0 24 24" width="24" height="24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
      </button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'PdfTocEditor',
  props: {
    modelValue: {
      type: Array,
      default: () => []
    }
  },
  emits: ['update:modelValue', 'page-focus'],
  data() {
    return {
      localToc: [],
      activeIndex: -1,
      expandedState: {}, // 存储每个条目的展开状态，key为条目索引
      draggingIndex: -1,
      dragOverIndex: -1,
      dragPosition: null // 'top' or 'bottom'
    };
  },
  computed: {
    // 计算可见的条目
    visibleRows() {
      const rows = [];
      let hideLevel = Infinity; // 当前隐藏的层级阈值

      this.localToc.forEach((item, index) => {
        // 默认所有条目都是展开的
        const isExpanded = this.expandedState[index] !== false;

        // 如果当前条目的层级小于等于隐藏阈值，说明它跳出了之前的折叠区域
        if (item.level <= hideLevel) {
          hideLevel = Infinity; // 重置隐藏阈值
          
          // 添加到可见列表
          rows.push({
            item,
            originalIndex: index,
            hasChildren: this.hasChildren(index),
            isExpanded: isExpanded
          });

          // 如果当前条目是折叠状态，且有子节点，则设置隐藏阈值
          // 任何层级大于当前条目的后续节点都将被隐藏
          if (!isExpanded) {
            hideLevel = item.level;
          }
        }
        // 否则（item.level > hideLevel），该条目被隐藏，不添加到 rows
      });

      return rows;
    }
  },
  watch: {
    modelValue: {
      handler(newVal) {
        this.localToc = JSON.parse(JSON.stringify(newVal || []));
        // 清理多余的状态（可选），或者不做处理，因为 computed 中有默认值
      },
      immediate: true,
      deep: true
    }
  },
  methods: {
    hasChildren(index) {
      if (index >= this.localToc.length - 1) return false;
      return this.localToc[index + 1].level > this.localToc[index].level;
    },
    toggleExpand(index) {
      // 如果未定义，默认为 true，取反即为 false
      if (this.expandedState[index] === undefined) {
        this.expandedState[index] = false;
      } else {
        this.expandedState[index] = !this.expandedState[index];
      }
    },
    emitUpdate() {
      this.$emit('update:modelValue', this.localToc);
    },
    handleRowClick(index) {
      this.activeIndex = index;
    },
    removeItem(index) {
      this.localToc.splice(index, 1);
      if (this.activeIndex === index) {
        this.activeIndex = -1;
      } else if (this.activeIndex > index) {
        this.activeIndex--;
      }
      this.emitUpdate();
    },
    addItem() {
      const newItem = {
        level: 1,
        page: 1,
        title: ''
      };

      if (this.activeIndex >= 0 && this.activeIndex < this.localToc.length) {
        // Insert after active item
        // Inherit page number and level from active item
        newItem.page = this.localToc[this.activeIndex].page;
        newItem.level = this.localToc[this.activeIndex].level;
        
        this.localToc.splice(this.activeIndex + 1, 0, newItem);
        this.activeIndex = this.activeIndex + 1;
      } else {
        this.localToc.push(newItem);
        this.activeIndex = this.localToc.length - 1;
      }

      this.emitUpdate();
      this.$nextTick(() => {
        // Scroll to the new item
        const activeRow = this.$el.querySelector('.toc-row.active');
        if (activeRow) {
          activeRow.scrollIntoView({ behavior: 'smooth', block: 'center' });
        } else {
          const container = this.$el.querySelector('.toc-list-container');
          if (container) container.scrollTop = container.scrollHeight;
        }
      });
    },
    getSubtreeRange(startIndex) {
      if (startIndex < 0 || startIndex >= this.localToc.length) return {start:0, end:0};
      const startItem = this.localToc[startIndex];
      let endIndex = startIndex + 1;
      while (endIndex < this.localToc.length) {
          if (this.localToc[endIndex].level <= startItem.level) {
              break;
          }
          endIndex++;
      }
      return { start: startIndex, end: endIndex };
    },
    onDragStart(e, index) {
      this.draggingIndex = index;
      e.dataTransfer.effectAllowed = 'move';
      // 尝试设置拖拽图像为整行，但由于我们只拖拽handle，可能需要调整
      // 这里简单处理，让浏览器默认显示
    },
    onDragEnd() {
      this.draggingIndex = -1;
      this.dragOverIndex = -1;
      this.dragPosition = null;
    },
    onDragOver(e, index) {
      // 防止拖拽到自己或自己的子树中
      const draggedRange = this.getSubtreeRange(this.draggingIndex);
      if (index >= draggedRange.start && index < draggedRange.end) {
          return; 
      }

      this.dragOverIndex = index;
      
      const rect = e.currentTarget.getBoundingClientRect();
      const mid = rect.top + rect.height / 2;
      this.dragPosition = e.clientY < mid ? 'top' : 'bottom';
    },
    onDrop(e, index) {
      if (this.draggingIndex === -1) return;
      
      const draggedRange = this.getSubtreeRange(this.draggingIndex);
      // 再次检查有效性
      if (index >= draggedRange.start && index < draggedRange.end) return;

      const targetRange = this.getSubtreeRange(index);
      
      let insertIndex;
      if (this.dragPosition === 'top') {
          insertIndex = targetRange.start;
      } else {
          // 如果是放在下面，应该是放在目标子树的后面
          insertIndex = targetRange.end;
      }
      
      const itemsToMove = this.localToc.slice(draggedRange.start, draggedRange.end);
      
      // 先删除
      this.localToc.splice(draggedRange.start, itemsToMove.length);
      
      // 如果删除的位置在插入位置之前，插入位置需要前移
      if (draggedRange.start < insertIndex) {
          insertIndex -= itemsToMove.length;
      }
      
      // 插入
      this.localToc.splice(insertIndex, 0, ...itemsToMove);
      
      this.emitUpdate();
      this.onDragEnd();
    },
    changeLevel(index, delta) {
      const newLevel = this.localToc[index].level + delta;
      if (newLevel >= 1 && newLevel <= 6) {
        this.localToc[index].level = newLevel;
        this.emitUpdate();
      }
    },
    expandAll() {
      const newState = {};
      this.localToc.forEach((_, index) => {
        newState[index] = true;
      });
      this.expandedState = newState;
    },
    collapseAll() {
      const newState = {};
      this.localToc.forEach((_, index) => {
        newState[index] = false;
      });
      this.expandedState = newState;
    }
  }
};
</script>

<style scoped>
.pdf-toc-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: #fff;
  position: relative;
}

.toc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid #eee;
  background: #fff;
}

.header-title {
  font-weight: 600;
  color: #333;
  font-size: 15px;
}

.toc-list-container {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 80px;
}

.toc-row {
  display: flex;
  align-items: stretch;
  border-bottom: 1px solid #f5f5f5;
  transition: background-color 0.2s;
}

.toc-row:hover {
  background-color: #f8f9fa;
}

.toc-row.active {
  background-color: #e8f0fe;
}

.indent-control {
  display: flex;
  background-color: #fafafa;
  border-right: 1px solid #eee;
  flex-shrink: 0;
}

.indent-line {
  width: 24px;
  border-right: 1px dashed #e0e0e0;
  flex-shrink: 0;
}

.indent-toggle {
  width: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #5f6368;
  transition: color 0.2s;
}

.indent-toggle:hover {
  color: #1a73e8;
}

.indent-toggle svg {
  transition: transform 0.2s;
}

.indent-toggle svg.rotated {
  transform: rotate(90deg);
}

.toc-content-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 8px 12px;
  gap: 12px;
}

.move-controls {
  display: flex;
  gap: 2px;
  opacity: 0.3;
  transition: opacity 0.2s;
}

.toc-row:hover .move-controls,
.toc-row:hover .drag-handle {
  opacity: 1;
}

.clean-input {
  border: none;
  background: transparent;
  padding: 6px;
  font-size: 14px;
  border-radius: 4px;
  transition: all 0.2s;
}

.clean-input:focus {
  background: #fff;
  box-shadow: 0 0 0 2px #1a73e8;
  outline: none;
}

.title-input-wrapper {
  flex: 1;
}

.title-input {
  width: 100%;
  color: #333;
}

.title-input.is-chapter {
  font-weight: 600;
  font-size: 15px;
}

.page-input-wrapper {
  display: flex;
  align-items: center;
  background: #f1f3f4;
  border-radius: 4px;
  padding: 0 8px;
  width: 80px;
}

.page-label {
  font-size: 12px;
  color: #5f6368;
  margin-right: 4px;
}

.page-input {
  width: 100%;
  text-align: center;
  font-family: 'Roboto Mono', monospace;
  font-weight: 500;
  padding: 4px;
}

.btn-icon {
  background: none;
  border: none;
  cursor: pointer;
  color: #5f6368;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-icon:hover:not(:disabled) {
  background-color: #e8eaed;
  color: #202124;
}

.btn-icon.mini {
  width: 24px;
  height: 24px;
  padding: 0;
}

.btn-icon.delete:hover {
  background-color: #fce8e6;
  color: #d93025;
}

.btn-icon.jump {
  color: #1a73e8;
  margin-right: 4px;
}

.drag-handle {
  cursor: grab;
  color: #9aa0a6;
  display: flex;
  align-items: center;
  padding: 4px;
  margin-left: 8px;
  opacity: 0.3;
  transition: opacity 0.2s;
}

.drag-handle:active {
  cursor: grabbing;
}

.toc-row.drag-over-top {
  border-top: 2px solid #1a73e8;
}

.toc-row.drag-over-bottom {
  border-bottom: 2px solid #1a73e8;
}

.toc-row.is-dragging {
  opacity: 0.5;
  background: #f1f3f4;
}

.btn-icon.jump:hover {
  background-color: #e8f0fe;
}

.btn-text {
  background: none;
  border: none;
  color: #1a73e8;
  cursor: pointer;
  font-size: 13px;
  margin-left: 12px;
}

.toc-fab {
  position: absolute;
  bottom: 24px;
  right: 24px;
}

.fab-button {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background-color: #1a73e8;
  color: white;
  border: none;
  box-shadow: 0 6px 10px rgba(0,0,0,0.14), 0 1px 18px rgba(0,0,0,0.12), 0 3px 5px rgba(0,0,0,0.2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.fab-button:hover {
  background-color: #1765cc;
  transform: scale(1.05);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #5f6368;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.5;
}

.btn-primary {
  margin-top: 16px;
  padding: 8px 24px;
  background: #1a73e8;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
</style>
