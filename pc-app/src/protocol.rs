//! Codage des trames du protocole OmarchyConnect (v1).
//! Le schéma exact est décrit dans `PROTOCOL.md` à la racine du dépôt.

use serde_json::{json, Map, Value};

pub const VERSION: u32 = 1;

/// Trame entrante : `{"v":1,"t":"...", ...}` + les champs restants.
#[derive(Debug)]
pub struct Incoming {
    pub t: String,
    pub fields: Map<String, Value>,
}

impl Incoming {
    pub fn parse(raw: &str) -> Option<Incoming> {
        let value: Value = serde_json::from_str(raw).ok()?;
        let mut obj = value.as_object()?.clone();
        let t = obj.remove("t")?.as_str()?.to_string();
        obj.remove("v");
        Some(Incoming { t, fields: obj })
    }

    pub fn str(&self, key: &str) -> Option<&str> {
        self.fields.get(key).and_then(|v| v.as_str())
    }
}

/// Construit une trame sortante avec `v` et `t` injectés.
pub fn frame(t: &str, body: Value) -> String {
    let mut map = body.as_object().cloned().unwrap_or_else(Map::new);
    map.insert("v".into(), json!(VERSION));
    map.insert("t".into(), json!(t));
    Value::Object(map).to_string()
}

pub fn error(code: &str, message: &str) -> String {
    frame(
        "error",
        json!({ "code": code, "message": message }),
    )
}
