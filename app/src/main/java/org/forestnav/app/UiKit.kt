package org.forestnav.app

/** Minimal spinner/text listeners without extra imports (shared settings UI). */
class SimpleSelected(val onPos: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long,
    ) = onPos(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
}

class SimpleWatcher(val onText: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onText(s?.toString().orEmpty())
    }
}
