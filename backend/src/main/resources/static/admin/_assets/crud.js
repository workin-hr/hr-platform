function crudOpenEdit(modalId, btn) {
  const modal = document.getElementById(modalId);
  if (!modal || !btn) return;
  const form = modal.querySelector('form');
  if (!form) return;
  const action = form.querySelector('[name="action"]');
  if (action && action.dataset.edit) action.value = action.dataset.edit;
  const idField = form.querySelector('[name="id"]');
  if (idField && btn.dataset.id) idField.value = btn.dataset.id;
  Array.from(btn.attributes).forEach((attr) => {
    if (!attr.name.startsWith('data-') || attr.name === 'data-id') return;
    const fieldName = attr.name.slice(5).replace(/-/g, '_');
    const field = form.querySelector('[name="' + fieldName + '"]');
    if (field) field.value = attr.value;
  });
  const titleEl = modal.querySelector('[data-edit-title]');
  if (titleEl && titleEl.dataset.editTitleText) titleEl.textContent = titleEl.dataset.editTitleText;
  modal.classList.add('open');
}

function crudOpenAdd(modalId) {
  const modal = document.getElementById(modalId);
  if (!modal) return;
  const form = modal.querySelector('form');
  if (form) {
    form.reset();
    const action = form.querySelector('[name="action"]');
    if (action && action.dataset.add) action.value = action.dataset.add;
    const idField = form.querySelector('[name="id"]');
    if (idField) idField.value = '0';
  }
  const titleEl = modal?.querySelector('[data-edit-title]');
  if (titleEl && titleEl.dataset.addTitle) titleEl.textContent = titleEl.dataset.addTitle;
  modal.classList.add('open');
}

document.querySelectorAll('.modal-close, .modal-bg').forEach((el) => {
  el.addEventListener('click', (e) => {
    if (e.target.classList.contains('modal-bg') || e.target.classList.contains('modal-close')) {
      e.target.closest('.modal-bg')?.classList.remove('open');
    }
  });
});
