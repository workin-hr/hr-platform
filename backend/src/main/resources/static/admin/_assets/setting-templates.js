(function () {
  var optionModal = document.getElementById('settingOptionModal');
  var definitionModal = document.getElementById('settingDefinitionModal');
  if (!optionModal && !definitionModal) {
    return;
  }

  var labels = window.WorkinSettingTemplatesLabels || {
    add: 'Add option',
    edit: 'Edit option',
  };

  function parseJsonAttr(el, attr) {
    try {
      return JSON.parse(el.getAttribute(attr) || '{}');
    } catch (e) {
      return {};
    }
  }

  function setOptionValueLocked(locked) {
    var valueEl = document.getElementById('settingOptionValue');
    var hint = document.getElementById('settingOptionValueHint');
    var lockedHint = document.getElementById('settingOptionValueLockedHint');
    if (!valueEl) return;
    valueEl.readOnly = !!locked;
    if (hint) hint.hidden = !!locked;
    if (lockedHint) lockedHint.hidden = !locked;
  }

  function openOptionAdd(defId, defLabel) {
    if (!optionModal) return;
    var titleEl = document.getElementById('settingOptionModalTitle');
    var defEl = document.getElementById('settingOptionModalDef');
    if (titleEl) titleEl.textContent = labels.add;
    if (defEl) defEl.textContent = defLabel || '';
    document.getElementById('settingOptionAction').value = 'add_option';
    document.getElementById('settingOptionId').value = '';
    document.getElementById('settingOptionDefId').value = String(defId || '');
    document.getElementById('settingOptionValue').value = '';
    document.getElementById('settingOptionLabelAr').value = '';
    document.getElementById('settingOptionLabelEn').value = '';
    document.getElementById('settingOptionSort').value = '0';
    setOptionValueLocked(false);
    optionModal.classList.add('open');
  }

  function openOptionEdit(row) {
    if (!optionModal) return;
    var titleEl = document.getElementById('settingOptionModalTitle');
    var defEl = document.getElementById('settingOptionModalDef');
    if (titleEl) titleEl.textContent = labels.edit;
    if (defEl) defEl.textContent = row.definition_label || '';
    document.getElementById('settingOptionAction').value = 'edit_option';
    document.getElementById('settingOptionId').value = String(row.id || '');
    document.getElementById('settingOptionDefId').value = String(row.setting_definition_id || '');
    document.getElementById('settingOptionValue').value = row.value || '';
    document.getElementById('settingOptionLabelAr').value = row.label_ar || '';
    document.getElementById('settingOptionLabelEn').value = row.label_en || '';
    document.getElementById('settingOptionSort').value = String(row.sort_order || 0);
    setOptionValueLocked(!!row.in_use);
    optionModal.classList.add('open');
  }

  function openDefinitionEdit(row) {
    if (!definitionModal) return;
    document.getElementById('settingDefinitionId').value = String(row.id || '');
    document.getElementById('settingDefinitionKey').value = row.setting_key || '';
    document.getElementById('settingDefinitionLabelAr').value = row.label_ar || '';
    document.getElementById('settingDefinitionLabelEn').value = row.label_en || '';
    document.getElementById('settingDefinitionDescAr').value = row.description_ar || '';
    document.getElementById('settingDefinitionDescEn').value = row.description_en || '';
    document.getElementById('settingDefinitionSort').value = String(row.sort_order || 0);
    definitionModal.classList.add('open');
  }

  // Capture phase so clicks inside <summary> / portaled menus reach us reliably.
  document.addEventListener('click', function (e) {
    var blockedBtn = e.target.closest('[data-setting-option-blocked]');
    if (blockedBtn) {
      e.preventDefault();
      e.stopPropagation();
      window.alert(blockedBtn.getAttribute('data-setting-option-blocked') || '');
      return;
    }

    var addBtn = e.target.closest('[data-setting-option-add]');
    if (addBtn) {
      e.preventDefault();
      e.stopPropagation();
      openOptionAdd(addBtn.getAttribute('data-definition-id'), addBtn.getAttribute('data-definition-label'));
      return;
    }

    var editOptionBtn = e.target.closest('[data-setting-option-edit]');
    if (editOptionBtn) {
      e.preventDefault();
      e.stopPropagation();
      openOptionEdit(parseJsonAttr(editOptionBtn, 'data-setting-option'));
      return;
    }

    var editDefBtn = e.target.closest('[data-setting-definition-edit]');
    if (editDefBtn) {
      e.preventDefault();
      e.stopPropagation();
      openDefinitionEdit(parseJsonAttr(editDefBtn, 'data-setting-definition'));
      return;
    }

    if (e.target.closest('[data-setting-option-close]') && optionModal) {
      e.preventDefault();
      optionModal.classList.remove('open');
      return;
    }

    if (e.target.closest('[data-setting-definition-close]') && definitionModal) {
      e.preventDefault();
      definitionModal.classList.remove('open');
      return;
    }

    if (e.target === optionModal) {
      optionModal.classList.remove('open');
    }
    if (e.target === definitionModal) {
      definitionModal.classList.remove('open');
    }
  }, true);
})();
