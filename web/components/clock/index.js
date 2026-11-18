/* 示例组件：时钟。
   组件 id 必须和文件夹名一致（后端按文件夹名寻址 /comp/<id>/）。
   mount(root, api) 里想怎么画都行，别开全局变量污染别人才是唯一要求。 */
Cyril.component('clock', {
  mount: function (root, api) {
    root.innerHTML = '<div class="clock">'
      + '<b class="clock-t">--:--</b>'
      + '<span class="clock-d"></span></div>';
    var elT = root.querySelector('.clock-t');
    var elD = root.querySelector('.clock-d');
    var WD = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    /* api.every：立刻跑一次再起定时器，卸载时自动清掉 */
    api.every(function () {
      var d = new Date();
      elT.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
      elD.textContent = (d.getMonth() + 1) + '/' + d.getDate() + ' ' + WD[d.getDay()];
    }, 1000);
  },
  unmount: function () {}
});
