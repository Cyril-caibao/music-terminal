/* 示例组件：便签。
   演示「更宽的格子 + 可交互」：manifest.json 里 width 写 166，左栏会自动变宽，
   终端跟着让位（--rail-w 由宿主按最宽组件算，组件自己不用管）。 */
Cyril.component('note', {
  mount: function (root, api) {
    var KEY = 'cyril-comp-note';
    root.innerHTML = '<div class="note">'
      + '<div class="note-hd">便签</div>'
      + '<textarea class="note-ta" spellcheck="false" placeholder="随手记点什么…"></textarea>'
      + '</div>';
    var ta = root.querySelector('.note-ta');
    try { ta.value = localStorage.getItem(KEY) || ''; } catch (e) {}
    var save = function () {
      try { localStorage.setItem(KEY, ta.value); } catch (e) {}
    };
    ta.addEventListener('input', save);
    ta.addEventListener('blur', save);
    /* 关掉组件时把最后的内容落一次盘 */
    api.onUnmount = save;
  },
  unmount: function () {}
});
